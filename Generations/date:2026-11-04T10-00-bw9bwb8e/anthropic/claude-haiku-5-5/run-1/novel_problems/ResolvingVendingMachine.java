@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}


    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        RESERVED,
        COMMITTED,
        ROLLED_BACK,
        REJECTED
    }

    public static final class Snapshot {
        private final int[] availableStock;
        private final long availableFunds;
        private final long[] transactionIds;
        private final TransactionStatus[] statuses;

        private Snapshot(int[] availableStock, long availableFunds,
                         long[] transactionIds, TransactionStatus[] statuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.statuses = statuses;
        }

        public int[] getAvailableStock() {
            return availableStock.clone();
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public long[] getTransactionIds() {
            return transactionIds.clone();
        }

        public TransactionStatus[] getStatuses() {
            return statuses.clone();
        }
    }

    private static final class Record {
        final long id;
        final int[] quantities;
        final long cost;
        TransactionStatus status;
        Record next;

        Record(long id, int[] quantities, long cost, TransactionStatus status) {
            this.id = id;
            this.quantities = quantities;
            this.cost = cost;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final int[] prices;
    private final Dispenser dispenser;

    // Guarded by lock. stock[p] is available (unreserved) stock.
    // reservedStock[p] is stock held by RESERVED transactions.
    private final int[] stock;
    private final int[] reservedStock;
    private long funds;
    private long reservedFunds;

    // Guarded by lock. Chained hash table of all transaction records (single-use IDs).
    private Record[] buckets = new Record[16];
    private int size;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }
        int[] copy = prices.clone();
        for (int p : copy) {
            if (p < 0) {
                throw new IllegalArgumentException("prices must be nonnegative");
            }
        }
        this.prices = copy;
        this.dispenser = dispenser;
        this.stock = new int[copy.length];
        this.reservedStock = new int[copy.length];
        this.funds = 0L;
        this.reservedFunds = 0L;
    }

    public void restock(int product, int quantity) {
        checkProduct(product);
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }
        synchronized (lock) {
            long physical = (long) stock[product] + reservedStock[product];
            if ((long) quantity > Integer.MAX_VALUE - physical) {
                throw new IllegalArgumentException("restock would overflow stock");
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (lock) {
            long headroom = Long.MAX_VALUE - funds - reservedFunds;
            if (amount > headroom) {
                throw new IllegalArgumentException("insertFunds would overflow funds");
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0L) {
            throw new IllegalArgumentException("transactionId must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("quantities must not be null");
        }
        if (quantities.length != prices.length) {
            throw new IllegalArgumentException("quantities length must match product count");
        }
        int[] q = quantities.clone();
        boolean anyPositive = false;
        for (int v : q) {
            if (v < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (v > 0) {
                anyPositive = true;
            }
        }
        if (!anyPositive) {
            throw new IllegalArgumentException("bundle must contain at least one positive quantity");
        }
        long cost = computeCost(q);

        Record rec;
        synchronized (lock) {
            if (find(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transactionId: " + transactionId);
            }
            boolean available = cost <= funds;
            for (int p = 0; available && p < q.length; p++) {
                if (stock[p] < q[p]) {
                    available = false;
                }
            }
            if (!available) {
                insert(new Record(transactionId, q, cost, TransactionStatus.REJECTED));
                return false;
            }
            for (int p = 0; p < q.length; p++) {
                stock[p] -= q[p];
                reservedStock[p] += q[p];
            }
            funds -= cost;
            reservedFunds += cost;
            rec = new Record(transactionId, q, cost, TransactionStatus.RESERVED);
            insert(rec);
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, rec.quantities.clone());
        } catch (RuntimeException | Error e) {
            synchronized (lock) {
                rollbackLocked(rec);
            }
            throw e;
        }

        if (!dispensed) {
            synchronized (lock) {
                rollbackLocked(rec);
            }
            return false;
        }

        synchronized (lock) {
            return commitLocked(rec);
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            Record rec = find(transactionId);
            if (rec == null) {
                return false;
            }
            return rollbackLocked(rec);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[size];
            TransactionStatus[] statuses = new TransactionStatus[size];
            int idx = 0;
            for (Record head : buckets) {
                for (Record r = head; r != null; r = r.next) {
                    ids[idx] = r.id;
                    statuses[idx] = r.status;
                    idx++;
                }
            }
            return new Snapshot(stock.clone(), funds, ids, statuses);
        }
    }

    // ---- helpers; all lock-guarded helpers require lock held by caller ----

    private void checkProduct(int product) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product index: " + product);
        }
    }

    private long computeCost(int[] q) {
        long total = 0L;
        for (int p = 0; p < q.length; p++) {
            long term = (long) prices[p] * (long) q[p];
            if (term > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("bundle cost overflows");
            }
            total += term;
        }
        return total;
    }

    // Returns true if this call performed the rollback (reservation was still RESERVED).
    private boolean rollbackLocked(Record rec) {
        if (rec.status != TransactionStatus.RESERVED) {
            return false;
        }
        rec.status = TransactionStatus.ROLLED_BACK;
        for (int p = 0; p < rec.quantities.length; p++) {
            reservedStock[p] -= rec.quantities[p];
            stock[p] += rec.quantities[p];
        }
        reservedFunds -= rec.cost;
        funds += rec.cost;
        return true;
    }

    // Returns true only if the reservation was still RESERVED and is now committed.
    private boolean commitLocked(Record rec) {
        if (rec.status != TransactionStatus.RESERVED) {
            return false;
        }
        rec.status = TransactionStatus.COMMITTED;
        for (int p = 0; p < rec.quantities.length; p++) {
            reservedStock[p] -= rec.quantities[p];
        }
        reservedFunds -= rec.cost;
        return true;
    }

    private static int indexFor(long id, int length) {
        long h = id * 0x9E3779B97F4A7C15L;
        return (int) ((h >>> 32) & (length - 1));
    }

    private Record find(long id) {
        for (Record r = buckets[indexFor(id, buckets.length)]; r != null; r = r.next) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    private void insert(Record rec) {
        if ((size + 1) * 4 > buckets.length * 3) {
            resize();
        }
        int idx = indexFor(rec.id, buckets.length);
        rec.next = buckets[idx];
        buckets[idx] = rec;
        size++;
    }

    private void resize() {
        Record[] old = buckets;
        Record[] fresh = new Record[old.length * 2];
        for (Record head : old) {
            Record r = head;
            while (r != null) {
                Record nextRec = r.next;
                int idx = indexFor(r.id, fresh.length);
                r.next = fresh[idx];
                fresh[idx] = r;
                r = nextRec;
            }
        }
        buckets = fresh;
    }
}
