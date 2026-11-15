@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}


    public enum OrderStatus {
        RESERVED,
        SHIPPED,
        CANCELLED,
        REJECTED
    }

    public static final class OrderRecord {
        private final long id;
        private final long[] bundle;
        private final OrderStatus status;

        private OrderRecord(long id, long[] bundle, OrderStatus status) {
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

        public OrderStatus getStatus() {
            return status;
        }
    }

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

        public OrderRecord[] getOrders() {
            OrderRecord[] copy = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                copy[i] = orders[i];
            }
            return copy;
        }
    }

    private static final class Order {
        final long id;
        final long[] bundle;
        OrderStatus status;
        Order next;

        Order(long id, long[] bundle, OrderStatus status) {
            this.id = id;
            this.bundle = bundle;
            this.status = status;
        }
    }

    private static final int INITIAL_BUCKETS = 16;

    private final Object lock = new Object();
    private final int productCount;
    private final long[] available;
    private final long[] reserved;

    // Insertion-ordered registry of every accepted ID (including rejected reservations).
    private Order[] orderList;
    private int orderCount;

    // Hash index over orderList (chained).
    private Order[] buckets;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("initial stock must be a nonempty array");
        }
        for (long q : initialStock) {
            if (q < 0) {
                throw new IllegalArgumentException("stock quantities must be nonnegative");
            }
        }
        this.productCount = initialStock.length;
        this.available = initialStock.clone();
        this.reserved = new long[productCount];
        this.orderList = new Order[8];
        this.orderCount = 0;
        this.buckets = new Order[INITIAL_BUCKETS];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        checkPositiveId(orderId);
        long[] bundle = copyAndValidateBundle(quantities);

        synchronized (lock) {
            if (findLocked(orderId) != null) {
                throw new IllegalArgumentException("duplicate order id: " + orderId);
            }

            boolean canReserve = true;
            for (int i = 0; i < productCount; i++) {
                if (available[i] < bundle[i]) {
                    canReserve = false;
                    break;
                }
            }

            if (canReserve) {
                for (int i = 0; i < productCount; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
                registerLocked(new Order(orderId, bundle, OrderStatus.RESERVED));
                return true;
            }

            registerLocked(new Order(orderId, bundle, OrderStatus.REJECTED));
            return false;
        }
    }

    public boolean shipOrder(long orderId) {
        checkPositiveId(orderId);

        synchronized (lock) {
            Order order = findLocked(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }
            for (int i = 0; i < productCount; i++) {
                reserved[i] -= order.bundle[i];
            }
            order.status = OrderStatus.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        checkPositiveId(orderId);

        synchronized (lock) {
            Order order = findLocked(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }
            for (int i = 0; i < productCount; i++) {
                reserved[i] -= order.bundle[i];
                available[i] += order.bundle[i];
            }
            order.status = OrderStatus.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= productCount) {
            throw new IllegalArgumentException("invalid product index: " + product);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("restock quantity must be positive");
        }

        synchronized (lock) {
            long total = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("restock would overflow stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availCopy = available.clone();
            long[] resCopy = reserved.clone();
            OrderRecord[] records = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                Order o = orderList[i];
                records[i] = new OrderRecord(o.id, o.bundle.clone(), o.status);
            }
            return new Snapshot(availCopy, resCopy, records);
        }
    }

    // ---- helpers ----

    private void checkPositiveId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
    }

    private long[] copyAndValidateBundle(long[] quantities) {
        if (quantities == null) {
            throw new IllegalArgumentException("quantities must not be null");
        }
        if (quantities.length != productCount) {
            throw new IllegalArgumentException("bundle length must equal product count");
        }
        long[] copy = new long[productCount];
        boolean positive = false;
        for (int i = 0; i < productCount; i++) {
            long q = quantities[i];
            if (q < 0) {
                throw new IllegalArgumentException("bundle quantities must be nonnegative");
            }
            if (q > 0) {
                positive = true;
            }
            copy[i] = q;
        }
        if (!positive) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }
        return copy;
    }

    private int bucketIndex(long id, int length) {
        long h = id ^ (id >>> 32);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return (int) (h & (length - 1));
    }

    // Must hold lock.
    private Order findLocked(long id) {
        for (Order n = buckets[bucketIndex(id, buckets.length)]; n != null; n = n.next) {
            if (n.id == id) {
                return n;
            }
        }
        return null;
    }

    // Must hold lock.
    private void registerLocked(Order order) {
        if (orderCount == orderList.length) {
            Order[] grown = new Order[orderList.length * 2];
            System.arraycopy(orderList, 0, grown, 0, orderCount);
            orderList = grown;
        }
        orderList[orderCount++] = order;

        if (orderCount > buckets.length * 3 / 4) {
            rebuildBuckets(buckets.length * 2);
        } else {
            int idx = bucketIndex(order.id, buckets.length);
            order.next = buckets[idx];
            buckets[idx] = order;
        }
    }

    // Must hold lock.
    private void rebuildBuckets(int newLength) {
        Order[] fresh = new Order[newLength];
        for (int i = 0; i < orderCount; i++) {
            Order o = orderList[i];
            int idx = bucketIndex(o.id, newLength);
            o.next = fresh[idx];
            fresh[idx] = o;
        }
        buckets = fresh;
    }
}
