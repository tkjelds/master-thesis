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
        private final long[] available;
        private final long[] reserved;
        private final OrderRecord[] orders;

        private Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
            this.available = copyArray(available);
            this.reserved = copyArray(reserved);
            this.orders = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                OrderRecord order = orders[i];
                this.orders[i] = new OrderRecord(
                    order.orderId, order.quantities, order.status
                );
            }
        }

        public long[] getAvailable() {
            return copyArray(available);
        }

        public long[] getReserved() {
            return copyArray(reserved);
        }

        public OrderRecord[] getOrders() {
            OrderRecord[] result = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                OrderRecord order = orders[i];
                result[i] = new OrderRecord(
                    order.orderId, order.quantities, order.status
                );
            }
            return result;
        }
    }

    private static final class OrderEntry {
        private final long orderId;
        private final long[] quantities;
        private OrderStatus status;
        private OrderEntry next;

        private OrderEntry(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object mutex = new Object();
    private final long[] available;
    private final long[] reserved;
    private OrderEntry firstOrder;
    private OrderEntry lastOrder;
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        long[] copiedStock = copyArray(initialStock);
        for (int i = 0; i < copiedStock.length; i++) {
            if (copiedStock[i] < 0) {
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
            throw new IllegalArgumentException("Bundle must have one quantity per product");
        }

        long[] bundle = copyArray(quantities);
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

        synchronized (mutex) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Order ID has already been used");
            }

            boolean canReserve = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            OrderEntry entry = new OrderEntry(
                orderId,
                bundle,
                canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED
            );
            appendOrder(entry);

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

        synchronized (mutex) {
            OrderEntry order = findOrder(orderId);
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

        synchronized (mutex) {
            OrderEntry order = findOrder(orderId);
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

        synchronized (mutex) {
            long total = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("Restock would overflow total stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (mutex) {
            OrderRecord[] records = new OrderRecord[orderCount];
            OrderEntry current = firstOrder;
            int index = 0;
            while (current != null) {
                records[index++] = new OrderRecord(
                    current.orderId, current.quantities, current.status
                );
                current = current.next;
            }
            return new Snapshot(available, reserved, records);
        }
    }

    private OrderEntry findOrder(long orderId) {
        OrderEntry current = firstOrder;
        while (current != null) {
            if (current.orderId == orderId) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void appendOrder(OrderEntry entry) {
        if (lastOrder == null) {
            firstOrder = entry;
        } else {
            lastOrder.next = entry;
        }
        lastOrder = entry;
        orderCount++;
    }

    private static long[] copyArray(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
