@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    public enum OrderStatus {
        RESERVED,
        REJECTED,
        SHIPPED,
        CANCELLED
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] quantities;
        private final OrderStatus status;

        private OrderRecord(long orderId, long[] quantities, OrderStatus status) {
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

        public OrderStatus getStatus() {
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

    private static final class OrderState {
        private final long orderId;
        private final long[] quantities;
        private OrderStatus status;

        private OrderState(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private OrderState[] orders = new OrderState[0];
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        available = new long[initialStock.length];
        reserved = new long[initialStock.length];
        for (int i = 0; i < initialStock.length; i++) {
            if (initialStock[i] < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
            available[i] = initialStock[i];
        }
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        long[] bundle = new long[quantities.length];
        boolean hasPositiveQuantity = false;
        for (int i = 0; i < quantities.length; i++) {
            long quantity = quantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Bundle quantities must be nonnegative");
            }
            bundle[i] = quantity;
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must contain a positive quantity");
        }

        synchronized (lock) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Order ID has already been used");
            }

            ensureOrderCapacity();
            boolean canReserve = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            OrderStatus status = canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED;
            OrderState order = new OrderState(orderId, bundle, status);
            if (canReserve) {
                for (int i = 0; i < bundle.length; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }
            orders[orderCount++] = order;
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            OrderState order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int i = 0; i < order.quantities.length; i++) {
                reserved[i] -= order.quantities[i];
            }
            order.status = OrderStatus.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            OrderState order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int i = 0; i < order.quantities.length; i++) {
                reserved[i] -= order.quantities[i];
                available[i] += order.quantities[i];
            }
            order.status = OrderStatus.CANCELLED;
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
                throw new IllegalArgumentException("Restock would overflow total stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            OrderRecord[] records = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                OrderState order = orders[i];
                records[i] = new OrderRecord(order.orderId, order.quantities, order.status);
            }
            return new Snapshot(available, reserved, records);
        }
    }

    private OrderState findOrder(long orderId) {
        for (int i = 0; i < orderCount; i++) {
            if (orders[i].orderId == orderId) {
                return orders[i];
            }
        }
        return null;
    }

    private void ensureOrderCapacity() {
        if (orderCount < orders.length) {
            return;
        }

        int newCapacity = orders.length == 0 ? 4 : orders.length * 2;
        if (newCapacity < 0 || newCapacity <= orderCount) {
            newCapacity = Integer.MAX_VALUE;
        }
        OrderState[] expanded = new OrderState[newCapacity];
        for (int i = 0; i < orderCount; i++) {
            expanded[i] = orders[i];
        }
        orders = expanded;
    }

    private static long[] copyOf(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static OrderRecord[] copyOf(OrderRecord[] source) {
        OrderRecord[] copy = new OrderRecord[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
