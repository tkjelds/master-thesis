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
            this.quantities = copyArray(quantities);
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return copyArray(quantities);
        }

        public OrderStatus getStatus() {
            return status;
        }
    }

    public static final class Snapshot {
        private final long[] availableQuantities;
        private final long[] reservedQuantities;
        private final OrderRecord[] orders;

        private Snapshot(long[] availableQuantities, long[] reservedQuantities,
                         OrderRecord[] orders) {
            this.availableQuantities = copyArray(availableQuantities);
            this.reservedQuantities = copyArray(reservedQuantities);
            this.orders = copyArray(orders);
        }

        public long[] getAvailableQuantities() {
            return copyArray(availableQuantities);
        }

        public long[] getReservedQuantities() {
            return copyArray(reservedQuantities);
        }

        public OrderRecord[] getOrders() {
            return copyArray(orders);
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
        available = copyArray(initialStock);
        for (int i = 0; i < available.length; i++) {
            if (available[i] < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }
        reserved = new long[available.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        long[] bundle = copyAndValidateBundle(quantities);
        if (bundle.length != available.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        synchronized (lock) {
            if (findOrder(orderId) >= 0) {
                throw new IllegalArgumentException("Order ID has already been used");
            }

            boolean canReserve = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                }
            }

            ensureOrderCapacity();
            OrderEntry entry = new OrderEntry(
                    orderId, bundle, canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED);
            orders[orderCount++] = entry;

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
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != OrderStatus.RESERVED) {
                return false;
            }

            OrderEntry entry = orders[index];
            for (int i = 0; i < entry.quantities.length; i++) {
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
        synchronized (lock) {
            int index = findOrder(orderId);
            if (index < 0 || orders[index].status != OrderStatus.RESERVED) {
                return false;
            }

            OrderEntry entry = orders[index];
            for (int i = 0; i < entry.quantities.length; i++) {
                reserved[i] -= entry.quantities[i];
                available[i] += entry.quantities[i];
            }
            entry.status = OrderStatus.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restock quantity must be positive");
        }
        synchronized (lock) {
            if (product < 0 || product >= available.length) {
                throw new IllegalArgumentException("Invalid product index");
            }

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
            for (int i = 0; i < orderCount; i++) {
                OrderEntry entry = orders[i];
                records[i] = new OrderRecord(entry.orderId, entry.quantities, entry.status);
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
        int newCapacity = orders.length <= Integer.MAX_VALUE / 2
                ? orders.length * 2 : Integer.MAX_VALUE;
        OrderEntry[] expanded = new OrderEntry[newCapacity];
        for (int i = 0; i < orders.length; i++) {
            expanded[i] = orders[i];
        }
        orders = expanded;
    }

    private static long[] copyAndValidateBundle(long[] quantities) {
        if (quantities == null || quantities.length == 0) {
            throw new IllegalArgumentException("Bundle must be nonempty");
        }
        long[] copy = copyArray(quantities);
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
            throw new IllegalArgumentException("Bundle must reserve a positive quantity");
        }
        return copy;
    }

    private static long[] copyArray(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static OrderRecord[] copyArray(OrderRecord[] source) {
        OrderRecord[] copy = new OrderRecord[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
