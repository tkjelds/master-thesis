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
        public final long id;
        public final long[] quantities;
        public final Status status;

        private OrderRecord(long id, long[] quantities, Status status) {
            this.id = id;
            this.quantities = copyArray(quantities);
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final long[] available;
        public final long[] reserved;
        public final OrderRecord[] orders;

        private Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
            this.available = copyArray(available);
            this.reserved = copyArray(reserved);
            this.orders = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                OrderRecord order = orders[i];
                this.orders[i] = new OrderRecord(
                    order.id, order.quantities, order.status
                );
            }
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
            throw new IllegalArgumentException("Stock must be a nonempty array");
        }

        this.available = copyArray(initialStock);
        for (long quantity : this.available) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }
        this.reserved = new long[this.available.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Order bundle must match the product count");
        }

        long[] bundle = copyArray(quantities);
        boolean hasPositiveQuantity = false;
        for (long quantity : bundle) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Order quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Order bundle must contain a positive quantity");
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
            appendOrder(order);

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
            if (order == null || order.status != Status.RESERVED) {
                return false;
            }

            for (int i = 0; i < order.quantities.length; i++) {
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

            for (int i = 0; i < order.quantities.length; i++) {
                available[i] += order.quantities[i];
                reserved[i] -= order.quantities[i];
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
            int count = 0;
            for (Order order = firstOrder; order != null; order = order.next) {
                count++;
            }

            OrderRecord[] records = new OrderRecord[count];
            int index = 0;
            for (Order order = firstOrder; order != null; order = order.next) {
                records[index++] = new OrderRecord(
                    order.id, order.quantities, order.status
                );
            }
            return new Snapshot(available, reserved, records);
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

    private void appendOrder(Order order) {
        if (lastOrder == null) {
            firstOrder = order;
        } else {
            lastOrder.next = order;
        }
        lastOrder = order;
    }

    private static long[] copyArray(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
