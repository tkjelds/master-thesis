@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    public enum Status {
        RESERVED,
        REJECTED,
        SHIPPED,
        CANCELLED
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] quantities;
        private final Status status;

        private OrderRecord(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = copyOf(quantities);
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return copyOf(quantities);
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
            this.available = copyOf(available);
            this.reserved = copyOf(reserved);
            this.orders = copyOf(orders);
        }

        public long[] getAvailable() {
            return copyOf(available);
        }

        public long[] getReserved() {
            return copyOf(reserved);
        }

        public OrderRecord[] getOrders() {
            return copyOf(orders);
        }
    }

    private static final class OrderData {
        private final long orderId;
        private final long[] quantities;
        private Status status;

        private OrderData(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private OrderData[] orders = new OrderData[0];
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        this.available = copyOf(initialStock);
        this.reserved = new long[initialStock.length];

        for (long quantity : available) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        long[] bundle = copyOf(quantities);
        boolean hasPositiveQuantity = false;
        for (long quantity : bundle) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Bundle quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must contain a positive quantity");
        }

        synchronized (lock) {
            if (findOrder(orderId) >= 0) {
                throw new IllegalArgumentException("Order ID has already been used");
            }

            boolean canReserve = true;
            for (int product = 0; product < available.length; product++) {
                if (bundle[product] > available[product]) {
                    canReserve = false;
                    break;
                }
            }

            ensureOrderCapacity();
            if (canReserve) {
                for (int product = 0; product < available.length; product++) {
                    available[product] -= bundle[product];
                    reserved[product] += bundle[product];
                }
                orders[orderCount++] = new OrderData(orderId, bundle, Status.RESERVED);
                return true;
            }

            orders[orderCount++] = new OrderData(orderId, bundle, Status.REJECTED);
            return false;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != Status.RESERVED) {
                return false;
            }

            OrderData order = orders[index];
            for (int product = 0; product < reserved.length; product++) {
                reserved[product] -= order.quantities[product];
            }
            order.status = Status.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != Status.RESERVED) {
                return false;
            }

            OrderData order = orders[index];
            for (int product = 0; product < available.length; product++) {
                available[product] += order.quantities[product];
                reserved[product] -= order.quantities[product];
            }
            order.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= available.length) {
            throw new IllegalArgumentException("Invalid product index");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restock quantity must be positive");
        }

        synchronized (lock) {
            long total = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("Restock would overflow stock quantity");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            OrderRecord[] records = new OrderRecord[orderCount];
            for (int index = 0; index < orderCount; index++) {
                OrderData order = orders[index];
                records[index] = new OrderRecord(order.orderId, order.quantities, order.status);
            }
            return new Snapshot(available, reserved, records);
        }
    }

    private int findOrder(long orderId) {
        for (int index = 0; index < orderCount; index++) {
            if (orders[index].orderId == orderId) {
                return index;
            }
        }
        return -1;
    }

    private void ensureOrderCapacity() {
        if (orderCount < orders.length) {
            return;
        }

        int newCapacity = orders.length == 0 ? 4 : orders.length * 2;
        if (newCapacity < orders.length) {
            throw new IllegalStateException("Too many orders");
        }

        OrderData[] expanded = new OrderData[newCapacity];
        for (int index = 0; index < orderCount; index++) {
            expanded[index] = orders[index];
        }
        orders = expanded;
    }

    private static long[] copyOf(long[] source) {
        long[] copy = new long[source.length];
        for (int index = 0; index < source.length; index++) {
            copy[index] = source[index];
        }
        return copy;
    }

    private static OrderRecord[] copyOf(OrderRecord[] source) {
        OrderRecord[] copy = new OrderRecord[source.length];
        for (int index = 0; index < source.length; index++) {
            copy[index] = source[index];
        }
        return copy;
    }
}
