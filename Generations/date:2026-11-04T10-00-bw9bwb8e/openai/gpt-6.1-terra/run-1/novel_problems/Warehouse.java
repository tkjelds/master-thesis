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

        private OrderRecord(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final long[] available;
        public final long[] reserved;
        public final OrderRecord[] orders;

        private Snapshot(long[] available, long[] reserved, OrderRecord[] orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
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
    private int orderCount;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }

        long[] copy = new long[stock.length];
        for (int i = 0; i < stock.length; i++) {
            long quantity = stock[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative stock");
            }
            copy[i] = quantity;
        }

        available = copy;
        reserved = new long[copy.length];
    }

    // Reservations do not wait for inventory.
    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Invalid bundle length");
        }

        long[] copy = new long[quantities.length];
        boolean hasPositiveQuantity = false;
        for (int i = 0; i < quantities.length; i++) {
            long quantity = quantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative order quantity");
            }
            copy[i] = quantity;
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Empty bundle");
        }

        synchronized (lock) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Duplicate order ID");
            }

            boolean canReserve = true;
            for (int i = 0; i < copy.length; i++) {
                if (copy[i] > available[i]) {
                    canReserve = false;
                    break;
                }
            }

            Order order = new Order(
                orderId, copy, canReserve ? Status.RESERVED : Status.REJECTED
            );

            if (canReserve) {
                for (int i = 0; i < copy.length; i++) {
                    available[i] -= copy[i];
                    reserved[i] += copy[i];
                }
            }

            if (lastOrder == null) {
                firstOrder = order;
            } else {
                lastOrder.next = order;
            }
            lastOrder = order;
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
                long quantity = order.quantities[i];
                reserved[i] -= quantity;
                available[i] += quantity;
            }
            order.status = Status.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || product >= available.length || quantity <= 0) {
            throw new IllegalArgumentException("Invalid restock");
        }

        synchronized (lock) {
            long remainingCapacity = Long.MAX_VALUE - available[product] - reserved[product];
            if (quantity > remainingCapacity) {
                throw new IllegalArgumentException("Stock overflow");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availableCopy = new long[available.length];
            long[] reservedCopy = new long[reserved.length];
            for (int i = 0; i < available.length; i++) {
                availableCopy[i] = available[i];
                reservedCopy[i] = reserved[i];
            }

            OrderRecord[] records = new OrderRecord[orderCount];
            Order order = firstOrder;
            for (int i = 0; i < records.length; i++) {
                long[] quantitiesCopy = new long[order.quantities.length];
                for (int j = 0; j < quantitiesCopy.length; j++) {
                    quantitiesCopy[j] = order.quantities[j];
                }
                records[i] = new OrderRecord(order.id, quantitiesCopy, order.status);
                order = order.next;
            }

            return new Snapshot(availableCopy, reservedCopy, records);
        }
    }

    // Called only while holding lock.
    private Order findOrder(long orderId) {
        Order order = firstOrder;
        while (order != null) {
            if (order.id == orderId) {
                return order;
            }
            order = order.next;
        }
        return null;
    }
}
