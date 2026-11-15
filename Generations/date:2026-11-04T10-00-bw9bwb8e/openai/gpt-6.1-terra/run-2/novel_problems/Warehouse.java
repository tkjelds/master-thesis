@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    private static final long MAX_QUANTITY = 0x7fffffffffffffffL;

    public enum Status {
        RESERVED, REJECTED, SHIPPED, CANCELLED
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] quantities;
        private final Status status;

        private OrderRecord(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return copyArray(quantities);
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
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }

        public long[] getAvailable() {
            return copyArray(available);
        }

        public long[] getReserved() {
            return copyArray(reserved);
        }

        public OrderRecord[] getOrders() {
            OrderRecord[] copy = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                copy[i] = orders[i];
            }
            return copy;
        }
    }

    private static final class Order {
        private final long id;
        private final long[] quantities;
        private Status status;
        private Order next;

        private Order(long id, long[] quantities, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Order firstOrder;
    private Order lastOrder;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }

        long[] stockCopy = new long[initialStock.length];
        for (int i = 0; i < initialStock.length; i++) {
            long quantity = initialStock[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Stock cannot be negative");
            }
            stockCopy[i] = quantity;
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

        long[] bundle = new long[quantities.length];
        boolean hasPositiveQuantity = false;
        for (int i = 0; i < quantities.length; i++) {
            long quantity = quantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantity cannot be negative");
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

            boolean canReserve = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            Order order = new Order(
                orderId, bundle, canReserve ? Status.RESERVED : Status.REJECTED
            );

            if (canReserve) {
                for (int i = 0; i < bundle.length; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }

            if (lastOrder == null) {
                firstOrder = order;
            } else {
                lastOrder.next = order;
            }
            lastOrder = order;
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
            long capacity = MAX_QUANTITY - available[product] - reserved[product];
            if (quantity > capacity) {
                throw new IllegalArgumentException("Stock quantity would overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            int count = 0;
            for (Order order = firstOrder; order != null; order = order.next) {
                if (count == 0x7fffffff) {
                    throw new IllegalStateException("Too many orders for a snapshot");
                }
                count++;
            }

            OrderRecord[] records = new OrderRecord[count];
            int index = 0;
            for (Order order = firstOrder; order != null; order = order.next) {
                records[index++] = new OrderRecord(
                    order.id, copyArray(order.quantities), order.status
                );
            }

            return new Snapshot(copyArray(available), copyArray(reserved), records);
        }
    }

    private Order findOrder(long orderId) {
        for (Order order = firstOrder; order != null; order = order.next) {
            if (order.id == orderId) {
                return order;
            }
        }
        return null;
    }

    private static long[] copyArray(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
