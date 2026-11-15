@Warehouse.ThreadSafe
public class Warehouse {
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

        this.available = copyOf(initialStock);
        this.reserved = new long[initialStock.length];

        for (int i = 0; i < this.available.length; i++) {
            if (this.available[i] < 0) {
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
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("Bundle quantities must be nonnegative");
            }
            if (bundle[i] > 0) {
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
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            ensureOrderCapacity();
            OrderState state = new OrderState(
                orderId,
                bundle,
                canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED
            );

            if (canReserve) {
                for (int i = 0; i < bundle.length; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }

            orders[orderCount++] = state;
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != OrderStatus.RESERVED) {
                return false;
            }

            OrderState state = orders[index];
            for (int i = 0; i < state.quantities.length; i++) {
                reserved[i] -= state.quantities[i];
            }
            state.status = OrderStatus.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != OrderStatus.RESERVED) {
                return false;
            }

            OrderState state = orders[index];
            for (int i = 0; i < state.quantities.length; i++) {
                available[i] += state.quantities[i];
                reserved[i] -= state.quantities[i];
            }
            state.status = OrderStatus.CANCELLED;
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
            long committed = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - committed) {
                throw new IllegalArgumentException("Restock would overflow total stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            OrderRecord[] records = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                OrderState state = orders[i];
                records[i] = new OrderRecord(state.orderId, state.quantities, state.status);
            }
            return new Snapshot(available, reserved, records);
        }
    }

    private int findOrder(long orderId) {
        for (int i = 0; i < orderCount; i++) {
            if (orders[i].orderId == orderId) {
                return i;
            }
        }
        return -1;
    }

    private void ensureOrderCapacity() {
        if (orderCount < orders.length) {
            return;
        }

        int newCapacity;
        if (orders.length == 0) {
            newCapacity = 8;
        } else if (orders.length <= Integer.MAX_VALUE / 2) {
            newCapacity = orders.length * 2;
        } else if (orders.length < Integer.MAX_VALUE) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            throw new IllegalStateException("Cannot store more orders");
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
