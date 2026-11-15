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
        public final long orderId;
        public final long[] quantities;
        public final OrderStatus status;

        private OrderRecord(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities.clone();
            this.status = status;
        }

        public long[] getQuantities() {
            return quantities.clone();
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

    private static final class OrderState {
        final long orderId;
        final long[] quantities;
        OrderStatus status;

        OrderState(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private final Object lock = new Object();
    private final long[] available;
    private final long[] reserved;
    private OrderState[] orders = new OrderState[8];
    private int orderCount;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }

        long[] copiedStock = initialStock.clone();
        for (long quantity : copiedStock) {
            if (quantity < 0) {
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
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        long[] bundle = quantities.clone();
        boolean hasPositiveQuantity = false;
        for (long quantity : bundle) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Bundle quantities must be nonnegative");
            }
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
            for (int product = 0; product < bundle.length; product++) {
                if (bundle[product] > available[product]) {
                    canReserve = false;
                    break;
                }
            }

            ensureOrderCapacity();
            OrderStatus status = canReserve ? OrderStatus.RESERVED : OrderStatus.REJECTED;
            orders[orderCount++] = new OrderState(orderId, bundle, status);

            if (canReserve) {
                for (int product = 0; product < bundle.length; product++) {
                    available[product] -= bundle[product];
                    reserved[product] += bundle[product];
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
            OrderState order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int product = 0; product < available.length; product++) {
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

        synchronized (lock) {
            OrderState order = findOrder(orderId);
            if (order == null || order.status != OrderStatus.RESERVED) {
                return false;
            }

            for (int product = 0; product < available.length; product++) {
                reserved[product] -= order.quantities[product];
                available[product] += order.quantities[product];
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

        synchronized (lock) {
            long total = available[product] + reserved[product];
            if (total < available[product] || quantity > Long.MAX_VALUE - total) {
                throw new IllegalArgumentException("Restock would overflow total stock");
            }
            available[product] += quantity;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] availableCopy = available.clone();
            long[] reservedCopy = reserved.clone();
            OrderRecord[] orderCopies = new OrderRecord[orderCount];
            for (int i = 0; i < orderCount; i++) {
                OrderState order = orders[i];
                orderCopies[i] = new OrderRecord(order.orderId, order.quantities, order.status);
            }
            return new Snapshot(availableCopy, reservedCopy, orderCopies);
        }
    }

    private OrderState findOrder(long orderId) {
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
        if (orders.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Too many orders");
        }

        int newCapacity = orders.length <= Integer.MAX_VALUE / 2
                ? orders.length * 2
                : Integer.MAX_VALUE;
        OrderState[] expanded = new OrderState[newCapacity];
        System.arraycopy(orders, 0, expanded, 0, orderCount);
        orders = expanded;
    }
}
