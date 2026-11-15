@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}


    /**
     * Callback invoked outside the state monitor when a bundle is being dispensed.
     * Returning true commits the purchase (unless it was cancelled first);
     * returning false or throwing a RuntimeException rolls it back.
     */
    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    /** Lifecycle state of a transaction record. */
    public enum Status {
        PENDING,
        COMMITTED,
        ROLLED_BACK,
        REJECTED
    }

    /** Coherent, detached view of the machine at one instant. */
    public static final class Snapshot {
        private final long[] availableStock;
        private final long availableFunds;
        private final long[] transactionIds;
        private final Status[] statuses;

        private Snapshot(long[] availableStock, long availableFunds,
                         long[] transactionIds, Status[] statuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.statuses = statuses;
        }

        public long[] availableStock() {
            return availableStock.clone();
        }

        public long availableFunds() {
            return availableFunds;
        }

        public long[] transactionIds() {
            return transactionIds.clone();
        }

        public Status[] statuses() {
            return statuses.clone();
        }
    }

    /** Independent reservation record for one transaction. */
    private static final class Record {
        final long id;
        final int[] quantities;
        final long cost;
        Status status;
        Record bucketNext;   // hash chain link
        Record orderNext;    // insertion-order link

        Record(long id, int[] quantities, long cost, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.cost = cost;
            this.status = status;
        }
    }

    // Immutable configuration.
    private final int[] prices;
    private final Dispenser dispenser;

    // All mutable state below is guarded by 'lock'.
    private final Object lock = new Object();
    private final long[] stock;          // on-hand stock, including reserved
    private final long[] reservedStock;  // stock reserved by pending transactions
    private long funds;                  // money held, including reserved
    private long reservedFunds;          // money reserved by pending transactions

    private Record[] table = new Record[16];
    private int recordCount;
    private Record orderHead;
    private Record orderTail;

    /**
     * Policy: no operation blocks. Unavailable purchases return false immediately,
     * invalid input throws IllegalArgumentException, and arithmetic overflow throws
     * ArithmeticException, all before any state mutation.
     */
    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new NullPointerException("prices");
        }
        if (dispenser == null) {
            throw new NullPointerException("dispenser");
        }
        for (int p : prices) {
            if (p < 0) {
                throw new IllegalArgumentException("price must be nonnegative");
            }
        }
        this.prices = prices.clone();
        this.dispenser = dispenser;
        this.stock = new long[prices.length];
        this.reservedStock = new long[prices.length];
        this.funds = 0L;
        this.reservedFunds = 0L;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("product out of range");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }
        synchronized (lock) {
            // Stock total includes reserved stock, so overflow is checked against it.
            long newTotal = Math.addExact(stock[product], (long) quantity);
            stock[product] = newTotal;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (lock) {
            // Funds total includes reserved funds, so overflow is checked against it.
            long newTotal = Math.addExact(funds, amount);
            funds = newTotal;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        checkId(transactionId);
        if (quantities == null) {
            throw new IllegalArgumentException("quantities must not be null");
        }
        if (quantities.length != prices.length) {
            throw new IllegalArgumentException("bundle length must match product count");
        }
        boolean anyPositive = false;
        for (int q : quantities) {
            if (q < 0) {
                throw new IllegalArgumentException("bundle quantities must be nonnegative");
            }
            if (q > 0) {
                anyPositive = true;
            }
        }
        if (!anyPositive) {
            throw new IllegalArgumentException("bundle must contain at least one positive quantity");
        }

        // Compute cost with overflow checks before any mutation.
        int[] bundle = quantities.clone();
        long cost = 0L;
        for (int i = 0; i < bundle.length; i++) {
            long lineCost = Math.multiplyExact((long) prices[i], (long) bundle[i]);
            cost = Math.addExact(cost, lineCost);
        }

        Record record;
        synchronized (lock) {
            if (findLocked(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction id: " + transactionId);
            }

            boolean available = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > stock[i] - reservedStock[i]) {
                    available = false;
                    break;
                }
            }
            if (cost > funds - reservedFunds) {
                available = false;
            }

            if (!available) {
                // Registered and consumed, but nothing is reserved or changed.
                insertLocked(new Record(transactionId, bundle, cost, Status.REJECTED));
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                reservedStock[i] += bundle[i];
            }
            reservedFunds += cost;
            record = new Record(transactionId, bundle, cost, Status.PENDING);
            insertLocked(record);
        }

        // Dispense outside the monitor, using a detached copy.
        boolean ok;
        try {
            ok = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException | Error e) {
            synchronized (lock) {
                rollbackLocked(record);
            }
            throw e;
        }

        if (!ok) {
            synchronized (lock) {
                rollbackLocked(record);
            }
            return false;
        }

        synchronized (lock) {
            return commitLocked(record);
        }
    }

    public boolean cancel(long transactionId) {
        checkId(transactionId);
        synchronized (lock) {
            Record record = findLocked(transactionId);
            if (record == null) {
                return false;
            }
            return rollbackLocked(record);
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] available = new long[prices.length];
            for (int i = 0; i < available.length; i++) {
                available[i] = stock[i] - reservedStock[i];
            }
            long availFunds = funds - reservedFunds;

            long[] ids = new long[recordCount];
            Status[] statuses = new Status[recordCount];
            int idx = 0;
            for (Record r = orderHead; r != null; r = r.orderNext) {
                ids[idx] = r.id;
                statuses[idx] = r.status;
                idx++;
            }
            return new Snapshot(available, availFunds, ids, statuses);
        }
    }

    // ----- Helpers. All methods below that end in "Locked" require lock to be held. -----

    private static void checkId(long transactionId) {
        if (transactionId <= 0L) {
            throw new IllegalArgumentException("transaction id must be positive");
        }
    }

    private boolean commitLocked(Record r) {
        if (r.status != Status.PENDING) {
            return false;
        }
        for (int i = 0; i < r.quantities.length; i++) {
            stock[i] -= r.quantities[i];
            reservedStock[i] -= r.quantities[i];
        }
        funds -= r.cost;
        reservedFunds -= r.cost;
        r.status = Status.COMMITTED;
        return true;
    }

    private boolean rollbackLocked(Record r) {
        if (r.status != Status.PENDING) {
            return false;
        }
        for (int i = 0; i < r.quantities.length; i++) {
            reservedStock[i] -= r.quantities[i];
        }
        reservedFunds -= r.cost;
        r.status = Status.ROLLED_BACK;
        return true;
    }

    private static int bucketIndex(long id, int length) {
        int h = Long.hashCode(id);
        h ^= (h >>> 16);
        return (h & 0x7fffffff) % length;
    }

    private Record findLocked(long id) {
        int idx = bucketIndex(id, table.length);
        for (Record r = table[idx]; r != null; r = r.bucketNext) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    private void insertLocked(Record r) {
        if ((recordCount + 1) * 4 > table.length * 3) {
            resizeLocked(table.length * 2);
        }
        int idx = bucketIndex(r.id, table.length);
        r.bucketNext = table[idx];
        table[idx] = r;

        if (orderTail == null) {
            orderHead = r;
        } else {
            orderTail.orderNext = r;
        }
        orderTail = r;
        recordCount++;
    }

    private void resizeLocked(int newLength) {
        Record[] newTable = new Record[newLength];
        for (Record r = orderHead; r != null; r = r.orderNext) {
            int idx = bucketIndex(r.id, newLength);
            r.bucketNext = newTable[idx];
            newTable[idx] = r;
        }
        table = newTable;
    }
}
