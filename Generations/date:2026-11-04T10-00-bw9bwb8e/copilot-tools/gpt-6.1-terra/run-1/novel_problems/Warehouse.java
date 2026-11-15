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
        public final long orderId;
        public final long[] quantities;
        public final Status status;

        private OrderRecord(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final long[] available;
        public final long[] reserved;
        // Records are ordered by reservation registration.
        public final OrderRecord[] orders;

        private Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }
    }

    private static final class Order {
        private final long orderId;
        private final long[] quantities;
        private Status status;

        private Order(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Order[] orders = new Order[16];
    private int orderCount;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }

        long[] stockCopy = copyArray(stock);
        for (int i = 0; i < stockCopy.length; i++) {
            if (stockCopy[i] < 0) {
                throw new IllegalArgumentException("Stock cannot be negative");
            }
        }

        available = stockCopy;
        reserved = new long[stockCopy.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Invalid bundle length");
        }

        long[] bundle = copyArray(quantities);
        boolean hasPositiveQuantity = false;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("Quantities cannot be negative");
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

            boolean canReserve = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            ensureOrderCapacity();
            Order order = new Order(
                orderId, bundle, canReserve ? Status.RESERVED : Status.REJECTED
            );

            if (canReserve) {
                for (int i = 0; i < bundle.length; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }

            orders[orderCount] = order;
            orderCount++;
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

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
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (lock) {
            Order order = findOrder(orderId);
            if (order == null || order.status != Status.RESERVED) {
                return false;
            }

            for (int i = 0; i < reserved.length; i++) {
                reserved[i] -= order.quantities[i];
                available[i] += order.quantities[i];
            }
            order.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= available.length || quantity <= 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }

        synchronized (lock) {
            if (quantity > 0x7fffffffffffffffL - available[product] - reserved[product]) {
                throw new IllegalArgumentException("Stock would overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availableCopy = copyArray(available);
            long[] reservedCopy = copyArray(reserved);
            OrderRecord[] records = new OrderRecord[orderCount];

            for (int i = 0; i < orderCount; i++) {
                Order order = orders[i];
                records[i] = new OrderRecord(
                    order.orderId, copyArray(order.quantities), order.status
                );
            }

            return new Snapshot(availableCopy, reservedCopy, records);
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
        if (orders.length == 2147483647) {
            throw new OutOfMemoryError("Too many orders");
        }

        int newLength = orders.length <= 2147483647 / 2
            ? orders.length * 2
            : 2147483647;
        Order[] expanded = new Order[newLength];
        for (int i = 0; i < orderCount; i++) {
            expanded[i] = orders[i];
        }
        orders = expanded;
    }

    private static long[] copyArray(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
