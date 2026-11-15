@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    // ---------------------------------------------------------------------
    // Public nested types
    // ---------------------------------------------------------------------

    public enum Status {
        RESERVED,
        SHIPPED,
        CANCELLED,
        REJECTED
    }

    /** Immutable, detached view of one order. The bundle is copied on every access. */
    public static final class OrderRecord {
        private final long id;
        private final long[] bundle;
        private final Status status;

        private OrderRecord(long id, long[] bundle, Status status) {
            this.id = id;
            this.bundle = bundle;
            this.status = status;
        }

        public long getId() {
            return id;
        }

        public long[] getBundle() {
            return bundle.clone();
        }

        public Status getStatus() {
            return status;
        }
    }

    /** Immutable, detached, coherent view of the whole warehouse. */
    public static final class Snapshot {
        private final long[] available;
        private final long[] reserved;
        private final OrderRecord[] orders;

        private Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }

        public long[] getAvailable() {
            return available.clone();
        }

        public long[] getReserved() {
            return reserved.clone();
        }

        /** Records in order of registration (including rejected reservations). */
        public OrderRecord[] getOrders() {
            return orders.clone();
        }
    }

    // ---------------------------------------------------------------------
    // Internal order node
    // ---------------------------------------------------------------------

    private static final class Order {
        final long id;
        final long[] bundle;   // private copy, never exposed
        Status status;
        Order chain;           // hash bucket chain

        Order(long id, long[] bundle, Status status) {
            this.id = id;
            this.bundle = bundle;
            this.status = status;
        }
    }

    // ---------------------------------------------------------------------
    // State. All state is guarded by 'lock'.
    //
    // Policy: no operation blocks. reserveOrder never waits for inventory;
    // it either reserves the whole bundle or rejects it. Invalid arguments
    // throw IllegalArgumentException before any mutation. Unknown or terminal
    // order IDs in shipOrder/cancelOrder return false.
    // ---------------------------------------------------------------------

    private final Object lock = new Object();
    private final int productCount;
    private final long[] available;
    private final long[] reserved;

    private Order[] buckets = new Order[16];   // hash index, power-of-two size
    private Order[] registry = new Order[16];  // insertion-ordered list of all orders
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("initial stock must be a nonempty array");
        }
        long[] copy = initialStock.clone();
        for (long v : copy) {
            if (v < 0) {
                throw new IllegalArgumentException("stock quantities must be nonnegative");
            }
        }
        this.productCount = copy.length;
        this.available = copy;
        this.reserved = new long[copy.length];
    }

    /**
     * Atomically registers the order ID and reserves the whole bundle if every
     * product has sufficient available stock. Returns true if RESERVED, false if REJECTED.
     */
    public boolean reserveOrder(long orderId, long[] quantities) {
        checkId(orderId);
        if (quantities == null) {
            throw new IllegalArgumentException("quantities must not be null");
        }
        if (quantities.length != productCount) {
            throw new IllegalArgumentException("bundle length must equal product count");
        }
        long[] bundle = quantities.clone();   // copy before validation and retention
        boolean positive = false;
        for (long q : bundle) {
            if (q < 0) {
                throw new IllegalArgumentException("bundle quantities must be nonnegative");
            }
            if (q > 0) {
                positive = true;
            }
        }
        if (!positive) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        synchronized (lock) {
            if (lookup(orderId) != null) {
                throw new IllegalArgumentException("duplicate order id: " + orderId);
            }
            boolean fits = true;
            for (int i = 0; i < productCount; i++) {
                if (bundle[i] > available[i]) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                for (int i = 0; i < productCount; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }
            register(new Order(orderId, bundle, fits ? Status.RESERVED : Status.REJECTED));
            return fits;
        }
    }

    /** Changes RESERVED to SHIPPED; consumes reserved quantities exactly once. */
    public boolean shipOrder(long orderId) {
        checkId(orderId);
        synchronized (lock) {
            Order o = lookup(orderId);
            if (o == null || o.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < productCount; i++) {
                reserved[i] -= o.bundle[i];
            }
            o.status = Status.SHIPPED;
            return true;
        }
    }

    /** Changes RESERVED to CANCELLED; returns the entire reservation to available stock. */
    public boolean cancelOrder(long orderId) {
        checkId(orderId);
        synchronized (lock) {
            Order o = lookup(orderId);
            if (o == null || o.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < productCount; i++) {
                reserved[i] -= o.bundle[i];
                available[i] += o.bundle[i];
            }
            o.status = Status.CANCELLED;
            return true;
        }
    }

    /** Adds stock to the available quantity of a product. */
    public void restock(int product, long quantity) {
        if (product < 0 || product >= productCount) {
            throw new IllegalArgumentException("invalid product index: " + product);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("restock quantity must be positive");
        }
        synchronized (lock) {
            long used = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - used) {
                throw new IllegalArgumentException("restock would overflow stock");
            }
            available[product] += quantity;
        }
    }

    /** Returns a coherent, detached copy of all stock and order state. */
    public Snapshot snapshot() {
        synchronized (lock) {
            long[] av = available.clone();
            long[] rs = reserved.clone();
            OrderRecord[] recs = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                Order o = registry[i];
                recs[i] = new OrderRecord(o.id, o.bundle.clone(), o.status);
            }
            return new Snapshot(av, rs, recs);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers (called only while holding 'lock', except checkId/index math)
    // ---------------------------------------------------------------------

    private static void checkId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
    }

    private static int indexFor(long id, int capacity) {
        int h = Long.hashCode(id);
        h ^= (h >>> 16);
        return h & (capacity - 1);
    }

    private Order lookup(long id) {
        for (Order o = buckets[indexFor(id, buckets.length)]; o != null; o = o.chain) {
            if (o.id == id) {
                return o;
            }
        }
        return null;
    }

    private void register(Order o) {
        if (orderCount == registry.length) {
            Order[] grown = new Order[registry.length * 2];
            for (int i = 0; i < orderCount; i++) {
                grown[i] = registry[i];
            }
            registry = grown;
        }
        if ((orderCount + 1) * 4 > buckets.length * 3) {
            rehash(buckets.length * 2);
        }
        int idx = indexFor(o.id, buckets.length);
        o.chain = buckets[idx];
        buckets[idx] = o;
        registry[orderCount++] = o;
    }

    private void rehash(int newCapacity) {
        Order[] newBuckets = new Order[newCapacity];
        for (int i = 0; i < orderCount; i++) {
            Order o = registry[i];
            int idx = indexFor(o.id, newCapacity);
            o.chain = newBuckets[idx];
            newBuckets[idx] = o;
        }
        buckets = newBuckets;
    }
}
