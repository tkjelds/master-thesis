@Warehouse.ThreadSafe
public class Warehouse {
    @interface ThreadSafe {}


    public enum Status {
        RESERVED, SHIPPED, CANCELLED, REJECTED
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] quantities;
        private final Status status;

        OrderRecord(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities.clone();
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return quantities.clone();
        }

        public Status getStatus() {
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
            return orders.clone();
        }
    }

    private static final class Order {
        final long id;
        final long[] quantities;
        Status status;
        Order nextInBucket;

        Order(long id, long[] quantities, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private final int products;

    private Order[] buckets = new Order[16];
    private Order[] ordered = new Order[16];
    private int orderCount = 0;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("stock must be nonempty");
        }
        long[] copy = initialStock.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("negative stock");
            }
        }
        this.available = copy;
        this.reserved = new long[copy.length];
        this.products = copy.length;
    }

    private static int hash(long id, int length) {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return ((int) h) & (length - 1);
    }

    // must hold lock
    private Order find(long id) {
        Order o = buckets[hash(id, buckets.length)];
        while (o != null) {
            if (o.id == id) {
                return o;
            }
            o = o.nextInBucket;
        }
        return null;
    }

    // must hold lock
    private void register(Order o) {
        if (orderCount + 1 > buckets.length / 4 * 3) {
            Order[] nb = new Order[buckets.length * 2];
            for (int i = 0; i < orderCount; i++) {
                Order x = ordered[i];
                int idx = hash(x.id, nb.length);
                x.nextInBucket = nb[idx];
                nb[idx] = x;
            }
            buckets = nb;
        }
        int idx = hash(o.id, buckets.length);
        o.nextInBucket = buckets[idx];
        buckets[idx] = o;
        if (orderCount == ordered.length) {
            Order[] no = new Order[ordered.length * 2];
            for (int i = 0; i < orderCount; i++) {
                no[i] = ordered[i];
            }
            ordered = no;
        }
        ordered[orderCount++] = o;
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("quantities null");
        }
        long[] copy = quantities.clone();
        if (copy.length != products) {
            throw new IllegalArgumentException("wrong bundle length");
        }
        boolean positive = false;
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            if (copy[i] > 0) {
                positive = true;
            }
        }
        if (!positive) {
            throw new IllegalArgumentException("bundle has no positive quantity");
        }
        synchronized (lock) {
            if (find(orderId) != null) {
                throw new IllegalArgumentException("duplicate order id");
            }
            boolean ok = true;
            for (int i = 0; i < products; i++) {
                if (available[i] < copy[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                for (int i = 0; i < products; i++) {
                    available[i] -= copy[i];
                    reserved[i] += copy[i];
                }
                register(new Order(orderId, copy, Status.RESERVED));
                return true;
            } else {
                register(new Order(orderId, copy, Status.REJECTED));
                return false;
            }
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
        synchronized (lock) {
            Order o = find(orderId);
            if (o == null || o.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < products; i++) {
                reserved[i] -= o.quantities[i];
            }
            o.status = Status.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("order id must be positive");
        }
        synchronized (lock) {
            Order o = find(orderId);
            if (o == null || o.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < products; i++) {
                reserved[i] -= o.quantities[i];
                available[i] += o.quantities[i];
            }
            o.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= products) {
            throw new IllegalArgumentException("invalid product");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        synchronized (lock) {
            long room = Long.MAX_VALUE - available[product];
            room -= reserved[product];
            if (room < quantity) {
                throw new IllegalArgumentException("restock would overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            OrderRecord[] recs = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                Order o = ordered[i];
                recs[i] = new OrderRecord(o.id, o.quantities, o.status);
            }
            return new Snapshot(available.clone(), reserved.clone(), recs);
        }
    }
}
