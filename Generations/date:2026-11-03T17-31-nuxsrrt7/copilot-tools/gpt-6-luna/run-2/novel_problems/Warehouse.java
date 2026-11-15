@Warehouse.ThreadSafe
public class Warehouse {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Order[] orders;
    private int orderCount;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }

        available = copy(stock);
        for (int i = 0; i < available.length; i++) {
            if (available[i] < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }

        reserved = new long[available.length];
        orders = new Order[8];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        long[] bundle = copy(quantities);
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
            orders[orderCount++] = new Order(orderId, bundle, status);

            if (canReserve) {
                for (int i = 0; i < bundle.length; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            Order order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int i = 0; i < reserved.length; i++) {
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
            Order order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int i = 0; i < available.length; i++) {
                available[i] += order.quantities[i];
                reserved[i] -= order.quantities[i];
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
                Order order = orders[i];
                records[i] = new OrderRecord(order.orderId, order.quantities, order.status);
            }
            return new Snapshot(available, reserved, records);
        }
    }

    private Order findOrder(long orderId) {
        for (int i = 0; i < orderCount; i++) {
            if (orders[i].orderId == orderId) {
                return orders[i];
            }
        }
        return null;
    }

    private void ensureOrderCapacity() {
        if (orderCount == orders.length) {
            int newCapacity = orders.length <= Integer.MAX_VALUE / 2
                    ? orders.length * 2
                    : Integer.MAX_VALUE;
            Order[] expanded = new Order[newCapacity];
            for (int i = 0; i < orderCount; i++) {
                expanded[i] = orders[i];
            }
            orders = expanded;
        }
    }

    private static long[] copy(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

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
            this.quantities = copy(quantities);
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return copy(quantities);
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
            this.available = copy(available);
            this.reserved = copy(reserved);
            this.orders = orders;
        }

        public long[] getAvailable() {
            return copy(available);
        }

        public long[] getReserved() {
            return copy(reserved);
        }

        public OrderRecord[] getOrders() {
            OrderRecord[] result = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                OrderRecord order = orders[i];
                result[i] = new OrderRecord(order.orderId, order.quantities, order.status);
            }
            return result;
        }
    }

    private static final class Order {
        private final long orderId;
        private final long[] quantities;
        private OrderStatus status;

        private Order(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }
}
