@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    public enum Status {
        RESERVED, REJECTED, SHIPPED, CANCELLED
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] quantities;
        private final Status status;

        private OrderRecord(long orderId, long[] quantities, Status status) {
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
            return orders.clone();
        }
    }

    private static final class Order {
        private final long id;
        private final long[] quantities;
        private Status status;
        private Order next;

        private Order(long id, long[] quantities, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Order first;
    private Order last;
    private int orderCount;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }
        long[] copy = stock.clone();
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative stock");
            }
        }
        available = copy;
        reserved = new long[copy.length];
    }

    /*
     * Operations do not wait for inventory. Reservation fails immediately
     * when the complete bundle is unavailable.
     */
    public boolean reserveOrder(long orderId, long[] quantities) {
        requirePositiveId(orderId);
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Invalid bundle length");
        }

        long[] copy = quantities.clone();
        boolean hasPositive = false;
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative quantity");
            }
            if (quantity > 0) {
                hasPositive = true;
            }
        }
        if (!hasPositive) {
            throw new IllegalArgumentException("Empty bundle");
        }

        synchronized (lock) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Duplicate order ID");
            }

            boolean canReserve = true;
            for (int i = 0; i < copy.length; i++) {
                if (copy[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            if (orderCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many orders to snapshot");
            }
            Order order = new Order(
                    orderId, copy, canReserve ? Status.RESERVED : Status.REJECTED);

            if (canReserve) {
                for (int i = 0; i < copy.length; i++) {
                    available[i] -= copy[i];
                    reserved[i] += copy[i];
                }
            }

            if (last == null) {
                first = order;
            } else {
                last.next = order;
            }
            last = order;
            orderCount++;
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        requirePositiveId(orderId);
        synchronized (lock) {
            Order order = findOrder(orderId);
            if (order == null || order.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < reserved.length; i++) {
                reserved[i] -= order.quantities[i];
            }
            order.status = Status.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        requirePositiveId(orderId);
        synchronized (lock) {
            Order order = findOrder(orderId);
            if (order == null || order.status != Status.RESERVED) {
                return false;
            }
            for (int i = 0; i < reserved.length; i++) {
                long quantity = order.quantities[i];
                reserved[i] -= quantity;
                available[i] += quantity;
            }
            order.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= available.length || quantity <= 0) {
            throw new IllegalArgumentException("Invalid restock");
        }
        synchronized (lock) {
            if (quantity > Long.MAX_VALUE - available[product] - reserved[product]) {
                throw new ArithmeticException("Stock overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availableCopy = available.clone();
            long[] reservedCopy = reserved.clone();
            OrderRecord[] records = new OrderRecord[orderCount];
            Order current = first;
            for (int i = 0; i < records.length; i++) {
                records[i] = new OrderRecord(
                        current.id, current.quantities, current.status);
                current = current.next;
            }
            return new Snapshot(availableCopy, reservedCopy, records);
        }
    }

    private static void requirePositiveId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
    }

    // Called only while holding lock.
    private Order findOrder(long orderId) {
        for (Order current = first; current != null; current = current.next) {
            if (current.id == orderId) {
                return current;
            }
        }
        return null;
    }
}
