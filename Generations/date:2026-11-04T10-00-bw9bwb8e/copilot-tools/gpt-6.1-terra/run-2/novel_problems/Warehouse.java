@Warehouse.ThreadSafe
public final class Warehouse {
    @interface ThreadSafe {}

    public enum Status {
        RESERVED, REJECTED, SHIPPED, CANCELLED
    }

    public static final class OrderRecord {
        public final long orderId;
        public final long[] quantities;
        public final Status status;
        public final OrderRecord next;

        private OrderRecord(long orderId, long[] quantities, Status status,
                            OrderRecord next) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
            this.next = next;
        }
    }

    public static final class Snapshot {
        public final long[] available;
        public final long[] reserved;

        // Records are linked in order of reservation attempts, oldest first.
        public final OrderRecord orders;

        private Snapshot(long[] available, long[] reserved, OrderRecord orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }
    }

    private static final class Order {
        final long id;
        final long[] quantities;
        Status status;
        final Order next;

        Order(long id, long[] quantities, Status status, Order next) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private Order orders;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }

        long[] copy = stock.clone();
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Stock cannot be negative");
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
            throw new IllegalArgumentException("Invalid bundle length");
        }

        long[] bundle = quantities.clone();
        boolean hasPositiveQuantity = false;
        for (long quantity : bundle) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities cannot be negative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must not be empty");
        }

        synchronized (lock) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Order ID already used");
            }

            boolean canReserve = true;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            Order order = new Order(orderId, bundle,
                    canReserve ? Status.RESERVED : Status.REJECTED, orders);
            if (canReserve) {
                for (int i = 0; i < bundle.length; i++) {
                    available[i] -= bundle[i];
                    reserved[i] += bundle[i];
                }
            }
            orders = order;
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
            long remaining = 0x7fffffffffffffffL
                    - available[product] - reserved[product];
            if (quantity > remaining) {
                throw new ArithmeticException("Stock would overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            OrderRecord records = null;
            for (Order order = orders; order != null; order = order.next) {
                records = new OrderRecord(order.id, order.quantities.clone(),
                        order.status, records);
            }
            return new Snapshot(available.clone(), reserved.clone(), records);
        }
    }

    private Order findOrder(long orderId) {
        for (Order order = orders; order != null; order = order.next) {
            if (order.id == orderId) {
                return order;
            }
        }
        return null;
    }
}
