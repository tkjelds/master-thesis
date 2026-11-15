@Warehouse.ThreadSafe
public class Warehouse {
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

    private static final class OrderNode {
        private final long orderId;
        private final long[] quantities;
        private OrderStatus status;
        private OrderNode next;

        private OrderNode(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object mutex = new Object();
    private final long[] available;
    private final long[] reserved;
    private OrderNode firstOrder;
    private OrderNode lastOrder;
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        long[] stockCopy = initialStock.clone();
        for (long quantity : stockCopy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Stock quantities must be nonnegative");
            }
        }

        this.available = stockCopy;
        this.reserved = new long[stockCopy.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("Order quantities must not be null");
        }

        long[] bundle = quantities.clone();
        if (bundle.length != available.length) {
            throw new IllegalArgumentException("Order must contain one quantity per product");
        }

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
            throw new IllegalArgumentException("Order must request at least one product");
        }

        synchronized (mutex) {
            if (findOrder(orderId) != null) {
                throw new IllegalArgumentException("Order ID has already been used");
            }

            boolean canReserve = true;
            for (int product = 0; product < bundle.length; product++) {
                if (available[product] < bundle[product]) {
                    canReserve = false;
                    break;
                }
            }

            OrderNode order = new OrderNode(
                    orderId, bundle, canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED);
            if (canReserve) {
                for (int product = 0; product < bundle.length; product++) {
                    available[product] -= bundle[product];
                    reserved[product] += bundle[product];
                }
            }
            appendOrder(order);
            return canReserve;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }

        synchronized (mutex) {
            OrderNode order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int product = 0; product < order.quantities.length; product++) {
                reserved[product] -= order.quantities[product];
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
            OrderNode order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int product = 0; product < order.quantities.length; product++) {
                available[product] += order.quantities[product];
                reserved[product] -= order.quantities[product];
            }
            order.status = OrderStatus.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        synchronized (mutex) {
            if (product < 0 || product >= available.length) {
                throw new IllegalArgumentException("Invalid product index");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Restock quantity must be positive");
            }

            long committed = available[product] + reserved[product];
            if (quantity > Long.MAX_VALUE - committed) {
                throw new IllegalArgumentException("Restock would overflow stock quantity");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (mutex) {
            OrderRecord[] orderRecords = new OrderRecord[orderCount];
            OrderNode order = firstOrder;
            for (int index = 0; index < orderCount; index++) {
                orderRecords[index] = new OrderRecord(
                        order.orderId, order.quantities, order.status);
                order = order.next;
            }
            return new Snapshot(available, reserved, orderRecords);
        }
    }

    private OrderNode findOrder(long orderId) {
        OrderNode order = firstOrder;
        while (order != null) {
            if (order.orderId == orderId) {
                return order;
            }
            order = order.next;
        }
        return null;
    }

    private void appendOrder(OrderNode order) {
        if (lastOrder == null) {
            firstOrder = order;
        } else {
            lastOrder.next = order;
        }
        lastOrder = order;
        orderCount++;
    }
}
