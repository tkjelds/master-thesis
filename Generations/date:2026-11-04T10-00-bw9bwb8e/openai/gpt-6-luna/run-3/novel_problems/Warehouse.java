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

    private static final class OrderEntry {
        private final long orderId;
        private final long[] quantities;
        private OrderStatus status;

        private OrderEntry(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private OrderEntry[] orders = new OrderEntry[8];
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        long[] copy = initialStock.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }

        available = copy;
        reserved = new long[copy.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        long[] copy = quantities.clone();
        boolean hasPositiveQuantity = false;
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("Bundle quantities must be nonnegative");
            }
            if (copy[i] > 0) {
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
            for (int i = 0; i < available.length; i++) {
                if (copy[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            ensureOrderCapacity();
            OrderStatus status = canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED;
            orders[orderCount++] = new OrderEntry(orderId, copy, status);

            if (canReserve) {
                for (int i = 0; i < available.length; i++) {
                    available[i] -= copy[i];
                    reserved[i] += copy[i];
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
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != OrderStatus.RESERVED) {
                return false;
            }

            OrderEntry order = orders[index];
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
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != OrderStatus.RESERVED) {
                return false;
            }

            OrderEntry order = orders[index];
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
            if (quantity > Long.MAX_VALUE - available[product] - reserved[product]) {
                throw new IllegalArgumentException("Restock would overflow total stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availableCopy = available.clone();
            long[] reservedCopy = reserved.clone();
            OrderRecord[] orderCopies = new OrderRecord[orderCount];

            for (int i = 0; i < orderCount; i++) {
                OrderEntry order = orders[i];
                orderCopies[i] = new OrderRecord(
                    order.orderId, order.quantities, order.status
                );
            }

            return new Snapshot(availableCopy, reservedCopy, orderCopies);
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

        int newLength;
        if (orders.length <= Integer.MAX_VALUE / 2) {
            newLength = orders.length * 2;
        } else if (orders.length < Integer.MAX_VALUE) {
            newLength = Integer.MAX_VALUE;
        } else {
            throw new OutOfMemoryError("Too many orders");
        }

        OrderEntry[] expanded = new OrderEntry[newLength];
        for (int i = 0; i < orderCount; i++) {
            expanded[i] = orders[i];
        }
        orders = expanded;
    }
}
