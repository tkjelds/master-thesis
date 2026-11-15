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
            this.quantities = quantities.clone();
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return quantities.clone();
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
            this.available = available.clone();
            this.reserved = reserved.clone();
            this.orders = orders.clone();
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
        private final long orderId;
        private final long[] quantities;
        private OrderStatus status;

        private Order(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Order[] orders = new Order[8];
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        long[] copiedStock = initialStock.clone();
        for (long quantity : copiedStock) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }

        this.available = copiedStock;
        this.reserved = new long[copiedStock.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        long[] copiedQuantities = quantities.clone();
        boolean hasPositiveQuantity = false;
        for (long quantity : copiedQuantities) {
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
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Order ID has already been used");
            }

            boolean canReserve = true;
            for (int product = 0; product < available.length; product++) {
                if (copiedQuantities[product] > available[product]) {
                    canReserve = false;
                    break;
                }
            }

            OrderStatus status = canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED;
            Order order = new Order(orderId, copiedQuantities, status);
            ensureOrderCapacity();

            if (canReserve) {
                for (int product = 0; product < available.length; product++) {
                    available[product] -= copiedQuantities[product];
                    reserved[product] += copiedQuantities[product];
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
            Order order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int product = 0; product < reserved.length; product++) {
                reserved[product] -= order.quantities[product];
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

            for (int product = 0; product < available.length; product++) {
                available[product] += order.quantities[product];
                reserved[product] -= order.quantities[product];
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
            OrderRecord[] copiedOrders = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                Order order = orders[i];
                copiedOrders[i] = new OrderRecord(
                    order.orderId,
                    order.quantities,
                    order.status
                );
            }
            return new Snapshot(available, reserved, copiedOrders);
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
        if (orderCount < orders.length) {
            return;
        }

        int newCapacity = orders.length > Integer.MAX_VALUE / 2
            ? Integer.MAX_VALUE
            : orders.length * 2;
        if (newCapacity <= orderCount) {
            throw new OutOfMemoryError("Too many orders");
        }

        Order[] expanded = new Order[newCapacity];
        System.arraycopy(orders, 0, expanded, 0, orderCount);
        orders = expanded;
    }
}
