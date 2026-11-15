@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    private static final long MAX_QUANTITY = 9223372036854775807L;

    public enum Status {
        RESERVED,
        REJECTED,
        SHIPPED,
        CANCELLED
    }

    private static final class Monitor {
    }

    private static final class Order {
        final long id;
        final long[] quantities;
        Status status;
        Order next;

        Order(long id, long[] quantities, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
        }
    }

    public static final class OrderRecord {
        private final long orderId;
        private final long[] quantities;
        private final Status status;

        private OrderRecord(Order order) {
            this.orderId = order.id;
            this.quantities = copy(order.quantities);
            this.status = order.status;
        }

        public long getOrderId() {
            return orderId;
        }

        public long[] getQuantities() {
            return copy(quantities);
        }

        public Status getStatus() {
            return status;
        }
    }

    public static final class Snapshot {
        private final long[] available;
        private final long[] reserved;
        private final OrderRecord[] orders;

        private Snapshot(long[] available, long[] reserved,
                         OrderRecord[] orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }

        public long[] getAvailable() {
            return copy(available);
        }

        public long[] getReserved() {
            return copy(reserved);
        }

        // Records are immutable and ordered by reservation registration.
        public OrderRecord[] getOrders() {
            OrderRecord[] result = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                result[i] = orders[i];
            }
            return result;
        }
    }

    private final Monitor monitor = new Monitor();
    private final long[] available;
    private final long[] reserved;

    private Order firstOrder;
    private Order lastOrder;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }

        long[] initial = copy(stock);
        for (int i = 0; i < initial.length; i++) {
            if (initial[i] < 0) {
                throw new IllegalArgumentException(
                        "Stock quantities must be nonnegative");
            }
        }

        available = initial;
        reserved = new long[initial.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        requirePositiveId(orderId);

        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException(
                    "A bundle must contain one quantity per product");
        }

        long[] bundle = copy(quantities);
        boolean hasPositiveQuantity = false;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException(
                        "Bundle quantities must be nonnegative");
            }
            if (bundle[i] > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException(
                    "A bundle must contain a positive quantity");
        }

        synchronized (monitor) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Order ID already used");
            }

            boolean accepted = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    accepted = false;
                    break;
                }
            }

            // Allocate before changing either inventory or the order registry.
            Order order = new Order(
                    orderId, bundle,
                    accepted ? Status.RESERVED : Status.REJECTED);

            if (accepted) {
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
            return accepted;
        }
    }

    public boolean shipOrder(long orderId) {
        requirePositiveId(orderId);

        synchronized (monitor) {
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
        requirePositiveId(orderId);

        synchronized (monitor) {
            Order order = findOrder(orderId);
            if (order == null || order.status != Status.RESERVED) {
                return false;
            }

            for (int i = 0; i < available.length; i++) {
                reserved[i] -= order.quantities[i];
                available[i] += order.quantities[i];
            }
            order.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= available.length) {
            throw new IllegalArgumentException("Invalid product index");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Restock quantity must be positive");
        }

        synchronized (monitor) {
            // Preserve available + reserved <= MAX_QUANTITY for cancellation.
            long capacity = MAX_QUANTITY - available[product]
                    - reserved[product];
            if (quantity > capacity) {
                throw new IllegalArgumentException(
                        "Restock would overflow total product stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int count = 0;
            for (Order order = firstOrder; order != null; order = order.next) {
                if (count == 2147483647) {
                    throw new IllegalStateException(
                            "Too many order records for a snapshot array");
                }
                count++;
            }

            OrderRecord[] records = new OrderRecord[count];
            int index = 0;
            for (Order order = firstOrder; order != null; order = order.next) {
                records[index++] = new OrderRecord(order);
            }

            return new Snapshot(copy(available), copy(reserved), records);
        }
    }

    // Called only while holding monitor.
    private Order findOrder(long orderId) {
        for (Order order = firstOrder; order != null; order = order.next) {
            if (order.id == orderId) {
                return order;
            }
        }
        return null;
    }

    private static void requirePositiveId(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
    }

    private static long[] copy(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
