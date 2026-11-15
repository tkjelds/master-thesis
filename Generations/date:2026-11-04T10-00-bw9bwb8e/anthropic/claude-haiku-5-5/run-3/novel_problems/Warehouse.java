@Warehouse.ThreadSafe
public class Warehouse {
    @interface ThreadSafe {}


    public static final int RESERVED = 1;
    public static final int SHIPPED = 2;
    public static final int CANCELLED = 3;
    public static final int REJECTED = 4;

    public static final class OrderRecord {
        private final long id;
        private final long[] quantities;
        private final int status;

        OrderRecord(long id, long[] quantities, int status) {
            this.id = id;
            this.quantities = quantities.clone();
            this.status = status;
        }

        public long getId() {
            return id;
        }

        public long[] getQuantities() {
            return quantities.clone();
        }

        public int getStatus() {
            return status;
        }
    }

    public static final class Snapshot {
        private final long[] available;
        private final long[] reserved;
        private final OrderRecord[] orders;

        Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
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
        final long[] quantities;
        int status;
        Order next;

        Order(long id, long[] quantities, int status) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
        }
    }

    // Single synchronization protocol: every access to inventory and order state
    // happens while holding this monitor. No operation blocks; reservations that
    // cannot be satisfied are recorded as REJECTED and return false immediately.
    private final Object lock = new Object();
    private final int productCount;
    private final long[] available;
    private final long[] reserved;

    // Order registry: chained hash index plus insertion-ordered array.
    private Order[] buckets = new Order[16];
    private Order[] ordered = new Order[16];
    private int orderCount;

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
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        validateOrderId(orderId);
        validateBundle(quantities);
        long[] bundle = quantities.clone();

        synchronized (lock) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("duplicate order id: " + orderId);
            }

            boolean canFulfill = true;
            for (int i = 0; i < productCount; i++) {
                if (available[i] < bundle[i]) {
                    canFulfill = false;
                    break;
                }
            }

            if (canFulfill) {
                for (int i = 0; i < productCount; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
                register(new Order(orderId, bundle, RESERVED));
                return true;
            }

            register(new Order(orderId, bundle, REJECTED));
            return false;
        }
    }

    public boolean shipOrder(long orderId) {
        validateOrderId(orderId);
        synchronized (lock) {
            Order order = findOrder(orderId);
            if (order == null || order.status != RESERVED) {
                return false;
            }
            for (int i = 0; i < productCount; i++) {
                reserved[i] -= order.quantities[i];
            }
            order.status = SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        validateOrderId(orderId);
        synchronized (lock) {
            Order order = findOrder(orderId);
            if (order == null || order.status != RESERVED) {
                return false;
            }
            for (int i = 0; i < productCount; i++) {
                reserved[i] -= order.quantities[i];
                available[i] += order.quantities[i];
            }
            order.status = CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= productCount) {
            throw new IllegalArgumentException("invalid product: " + product);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("restock quantity must be positive");
        }
        synchronized (lock) {
            // available and reserved are both nonnegative, so this subtraction cannot overflow.
            long room = Long.MAX_VALUE - available[product] - reserved[product];
            if (quantity > room) {
                throw new IllegalArgumentException("restock would overflow stock for product " + product);
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availCopy = available.clone();
            long[] reservedCopy = reserved.clone();
            OrderRecord[] records = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                Order o = ordered[i];
                records[i] = new OrderRecord(o.id, o.quantities, o.status);
            }
            return new Snapshot(availCopy, reservedCopy, records);
        }
    }

    // ---- helpers; callers must hold lock where state is touched ----

    private void validateOrderId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
    }

    private void validateBundle(long[] quantities) {
        if (quantities == null) {
            throw new IllegalArgumentException("bundle must not be null");
        }
        if (quantities.length != productCount) {
            throw new IllegalArgumentException("bundle length must equal product count");
        }
        boolean positive = false;
        for (long q : quantities) {
            if (q < 0) {
                throw new IllegalArgumentException("bundle quantities must be nonnegative");
            }
            if (q > 0) {
                positive = true;
            }
        }
        if (!positive) {
            throw new IllegalArgumentException("bundle must contain at least one positive quantity");
        }
    }

    private static int indexFor(long id, int length) {
        long h = id * 0x9E3779B97F4A7C15L;
        int x = (int) (h ^ (h >>> 32));
        return x & (length - 1);
    }

    private Order findOrder(long id) {
        int idx = indexFor(id, buckets.length);
        for (Order o = buckets[idx]; o != null; o = o.next) {
            if (o.id == id) {
                return o;
            }
        }
        return null;
    }

    private void register(Order order) {
        if ((orderCount + 1) * 4 > buckets.length * 3) {
            resize();
        }
        if (orderCount == ordered.length) {
            Order[] grown = new Order[ordered.length * 2];
            System.arraycopy(ordered, 0, grown, 0, orderCount);
            ordered = grown;
        }
        ordered[orderCount++] = order;
        int idx = indexFor(order.id, buckets.length);
        order.next = buckets[idx];
        buckets[idx] = order;
    }

    private void resize() {
        Order[] newBuckets = new Order[buckets.length * 2];
        for (int i = 0; i < orderCount; i++) {
            Order o = ordered[i];
            int idx = indexFor(o.id, newBuckets.length);
            o.next = newBuckets[idx];
            newBuckets[idx] = o;
        }
        buckets = newBuckets;
    }
}
