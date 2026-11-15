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
            this.orders = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                OrderRecord order = orders[i];
                this.orders[i] = new OrderRecord(
                        order.orderId, order.quantities, order.status);
            }
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
                OrderRecord order = orders[i];
                copy[i] = new OrderRecord(
                        order.orderId, order.quantities, order.status);
            }
            return copy;
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

    private final Object mutex = new Object();
    private final int productCount;
    private final long[] available;
    private final long[] reserved;
    private OrderEntry[] orderEntries = new OrderEntry[8];
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException(
                    "Initial stock must be a nonempty array");
        }

        long[] stockCopy = initialStock.clone();
        for (long quantity : stockCopy) {
            if (quantity < 0) {
                throw new IllegalArgumentException(
                        "Initial stock quantities must be nonnegative");
            }
        }

        this.productCount = stockCopy.length;
        this.available = stockCopy;
        this.reserved = new long[productCount];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != productCount) {
            throw new IllegalArgumentException(
                    "Order bundle must contain one quantity per product");
        }

        long[] bundle = quantities.clone();
        boolean hasPositiveQuantity = false;
        for (long quantity : bundle) {
            if (quantity < 0) {
                throw new IllegalArgumentException(
                        "Order quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException(
                    "Order bundle must contain a positive quantity");
        }

        synchronized (mutex) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException(
                        "Order ID has already been used");
            }
            if (orderCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Order capacity exhausted");
            }

            boolean canReserve = true;
            for (int i = 0; i < productCount; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            OrderStatus status = canReserve
                    ? OrderStatus.RESERVED
                    : OrderStatus.REJECTED;
            OrderEntry entry = new OrderEntry(orderId, bundle, status);
            ensureOrderCapacity(orderCount + 1);

            if (canReserve) {
                for (int i = 0; i < productCount; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }
            orderEntries[orderCount++] = entry;
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (mutex) {
            OrderEntry entry = findOrder(orderId);
            if (entry == null || entry.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int i = 0; i < productCount; i++) {
                reserved[i] -= entry.quantities[i];
            }
            entry.status = OrderStatus.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (mutex) {
            OrderEntry entry = findOrder(orderId);
            if (entry == null || entry.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int i = 0; i < productCount; i++) {
                reserved[i] -= entry.quantities[i];
                available[i] += entry.quantities[i];
            }
            entry.status = OrderStatus.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= productCount) {
            throw new IllegalArgumentException("Invalid product index");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Restock quantity must be positive");
        }

        synchronized (mutex) {
            long total = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException(
                        "Restock would overflow total stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (mutex) {
            OrderRecord[] records = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                OrderEntry entry = orderEntries[i];
                records[i] = new OrderRecord(
                        entry.orderId, entry.quantities, entry.status);
            }
            return new Snapshot(available, reserved, records);
        }
    }

    private OrderEntry findOrder(long orderId) {
        for (int i = 0; i < orderCount; i++) {
            if (orderEntries[i].orderId == orderId) {
                return orderEntries[i];
            }
        }
        return null;
    }

    private void ensureOrderCapacity(int requiredCapacity) {
        if (requiredCapacity <= orderEntries.length) {
            return;
        }

        int newCapacity = orderEntries.length;
        while (newCapacity < requiredCapacity) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = Integer.MAX_VALUE;
            } else {
                newCapacity *= 2;
            }
        }

        OrderEntry[] expanded = new OrderEntry[newCapacity];
        System.arraycopy(orderEntries, 0, expanded, 0, orderCount);
        orderEntries = expanded;
    }
}
