@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}


    /**
     * Callback invoked outside the state monitor to perform the physical dispense.
     * Returning true commits the purchase (unless it was cancelled meanwhile);
     * returning false or throwing a RuntimeException rolls the reservation back.
     */
    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    /** Status of a transaction as recorded by the machine. */
    public enum TransactionStatus {
        PENDING,      // reserved, awaiting dispense result
        COMMITTED,    // purchase completed
        ROLLED_BACK,  // reservation released (dispense failed or cancelled)
        REJECTED      // never reserved (insufficient stock or funds)
    }

    /** Coherent, detached view of the machine state. */
    public static final class Snapshot {
        private final long[] availableStock;
        private final long availableFunds;
        private final long[] transactionIds;
        private final TransactionStatus[] transactionStatuses;

        private Snapshot(long[] availableStock, long availableFunds,
                         long[] transactionIds, TransactionStatus[] transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.transactionStatuses = transactionStatuses;
        }

        public long[] getAvailableStock() {
            return availableStock.clone();
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public long[] getTransactionIds() {
            return transactionIds.clone();
        }

        public TransactionStatus[] getTransactionStatuses() {
            return transactionStatuses.clone();
        }
    }

    /** Per-transaction reservation record. Mutable fields are guarded by lock. */
    private static final class Transaction {
        final long id;
        final int[] quantities;   // private copy, never exposed
        final long cost;
        TransactionStatus status;

        Transaction(long id, int[] quantities, long cost) {
            this.id = id;
            this.quantities = quantities;
            this.cost = cost;
        }
    }

    /** Hash-table entry for looking up transactions by ID. */
    private static final class Entry {
        final long key;
        final Transaction tx;
        Entry next;

        Entry(long key, Transaction tx, Entry next) {
            this.key = key;
            this.tx = tx;
            this.next = next;
        }
    }

    // Blocking policy: no operation blocks. Every operation completes immediately:
    // unavailable bundles return false, duplicate or invalid input throws
    // IllegalArgumentException, and cancel returns false when nothing is pending.
    private final Object lock = new Object();
    private final int[] prices;            // immutable after construction
    private final Dispenser dispenser;     // immutable after construction

    // All fields below are guarded by lock.
    private final long[] stock;            // available (unreserved) stock per product
    private final long[] reservedStock;    // reserved stock per product
    private long funds;                    // available (unreserved) funds
    private long reservedFunds;            // reserved funds
    private Entry[] buckets = new Entry[16];
    private int size;
    private Transaction[] log = new Transaction[16];
    private int logSize;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }
        this.prices = new int[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("price must be nonnegative: index " + i);
            }
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.stock = new long[prices.length];
        this.reservedStock = new long[prices.length];
    }

    public void restock(int product, int quantity) {
        checkProduct(product);
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }
        synchronized (lock) {
            long total = stock[product] + reservedStock[product];
            if (quantity > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("stock overflow");
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (lock) {
            long total = funds + reservedFunds;
            if (amount > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("funds overflow");
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
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
        long cost = 0L;
        for (int i = 0; i < q.length; i++) {
            if (q[i] < 0) {
                throw new IllegalArgumentException("quantity must be nonnegative: index " + i);
            }
            if (q[i] > 0) {
                anyPositive = true;
            }
            try {
                cost = Math.addExact(cost, Math.multiplyExact((long) q[i], (long) prices[i]));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("bundle cost overflow", e);
            }
        }
        if (!anyPositive) {
            throw new IllegalArgumentException("bundle must contain at least one positive quantity");
        }

        Transaction tx = new Transaction(transactionId, q, cost);

        synchronized (lock) {
            if (find(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction id: " + transactionId);
            }
            boolean available = funds >= cost;
            for (int i = 0; available && i < q.length; i++) {
                if (stock[i] < q[i]) {
                    available = false;
                }
            }
            if (!available) {
                tx.status = TransactionStatus.REJECTED;
                register(tx);
                return false;
            }
            for (int i = 0; i < q.length; i++) {
                stock[i] -= q[i];
                reservedStock[i] += q[i];
            }
            funds -= cost;
            reservedFunds += cost;
            tx.status = TransactionStatus.PENDING;
            register(tx);
        }

        boolean result = false;
        boolean done = false;
        try {
            result = dispenser.dispense(transactionId, q.clone());
            done = true;
        } finally {
            if (!done) {
                rollback(tx);
            }
        }

        if (result) {
            return commit(tx);
        }
        rollback(tx);
        return false;
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            return false;
        }
        synchronized (lock) {
            Transaction tx = find(transactionId);
            if (tx == null) {
                return false;
            }
            return rollbackLocked(tx);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] stockCopy = new long[stock.length];
            System.arraycopy(stock, 0, stockCopy, 0, stock.length);
            long[] ids = new long[logSize];
            TransactionStatus[] statuses = new TransactionStatus[logSize];
            for (int i = 0; i < logSize; i++) {
                ids[i] = log[i].id;
                statuses[i] = log[i].status;
            }
            return new Snapshot(stockCopy, funds, ids, statuses);
        }
    }

    // ---- internal helpers ----

    private void checkProduct(int product) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product index: " + product);
        }
    }

    private boolean commit(Transaction tx) {
        synchronized (lock) {
            if (tx.status != TransactionStatus.PENDING) {
                return false;   // cancellation already won
            }
            for (int i = 0; i < tx.quantities.length; i++) {
                reservedStock[i] -= tx.quantities[i];
            }
            reservedFunds -= tx.cost;
            tx.status = TransactionStatus.COMMITTED;
            return true;
        }
    }

    private boolean rollback(Transaction tx) {
        synchronized (lock) {
            return rollbackLocked(tx);
        }
    }

    /** Caller must hold lock. */
    private boolean rollbackLocked(Transaction tx) {
        if (tx.status != TransactionStatus.PENDING) {
            return false;
        }
        for (int i = 0; i < tx.quantities.length; i++) {
            reservedStock[i] -= tx.quantities[i];
            stock[i] += tx.quantities[i];
        }
        reservedFunds -= tx.cost;
        funds += tx.cost;
        tx.status = TransactionStatus.ROLLED_BACK;
        return true;
    }

    private static int indexFor(long id, int length) {
        long h = id * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & (length - 1);
    }

    /** Caller must hold lock. */
    private Transaction find(long id) {
        for (Entry e = buckets[indexFor(id, buckets.length)]; e != null; e = e.next) {
            if (e.key == id) {
                return e.tx;
            }
        }
        return null;
    }

    /** Caller must hold lock. Records the transaction in the hash table and the log. */
    private void register(Transaction tx) {
        if (size + 1 > buckets.length * 3 / 4) {
            resize();
        }
        int idx = indexFor(tx.id, buckets.length);
        buckets[idx] = new Entry(tx.id, tx, buckets[idx]);
        size++;

        if (logSize == log.length) {
            Transaction[] bigger = new Transaction[log.length * 2];
            System.arraycopy(log, 0, bigger, 0, logSize);
            log = bigger;
        }
        log[logSize++] = tx;
    }

    /** Caller must hold lock. */
    private void resize() {
        Entry[] old = buckets;
        Entry[] fresh = new Entry[old.length * 2];
        for (Entry head : old) {
            Entry e = head;
            while (e != null) {
                Entry next = e.next;
                int idx = indexFor(e.key, fresh.length);
                e.next = fresh[idx];
                fresh[idx] = e;
                e = next;
            }
        }
        buckets = fresh;
    }
}
