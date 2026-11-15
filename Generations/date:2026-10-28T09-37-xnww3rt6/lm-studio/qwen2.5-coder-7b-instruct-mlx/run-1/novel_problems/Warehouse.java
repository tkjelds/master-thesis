@Warehouse.ThreadSafe
class Warehouse {
    @interface ThreadSafe {}

    private final long[] available;
    private final long[] reserved;
    private final OrderRecord[] orders;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }
        this.available = stock.clone();
        this.reserved = new long[stock.length];
        this.orders = new OrderRecord[(int) (1e9)];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0 || quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Invalid order ID or bundle");
        }
        synchronized (this) {
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > available[i]) {
                    return false;
                }
            }
            for (int i = 0; i < quantities.length; i++) {
                available[i] -= quantities[i];
                reserved[i] += quantities[i];
            }
            orders[(int) orderId] = new OrderRecord(orderId, quantities, OrderStatus.RESERVED);
            return true;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0 || orders[(int) orderId] == null) {
            return false;
        }
        synchronized (this) {
            OrderRecord order = orders[(int) orderId];
            if (order.status != OrderStatus.RESERVED) {
                return false;
            }
            for (int i = 0; i < order.quantities.length; i++) {
                reserved[i] -= order.quantities[i];
                available[i] += order.quantities[i];
            }
            orders[(int) orderId].status = OrderStatus.SHIPPED;
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0 || orders[(int) orderId] == null) {
            return false;
        }
        synchronized (this) {
            OrderRecord order = orders[(int) orderId];
            if (order.status != OrderStatus.RESERVED) {
                return false;
            }
            for (int i = 0; i < order.quantities.length; i++) {
                reserved[i] -= order.quantities[i];
                available[i] += order.quantities[i];
            }
            orders[(int) orderId].status = OrderStatus.CANCELLED;
            return true;
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || quantity <= 0 || product >= available.length) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }
        synchronized (this) {
            if (((available[product] + reserved[product] + quantity) & 0xFFFFFFFFL) < (available[product] + reserved[product])) {
                throw new IllegalArgumentException("Restock would cause overflow");
            }
            available[product] += quantity;
        }
    }

    public OrderRecord[] snapshot() {
        synchronized (this) {
            OrderRecord[] snapshot = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                snapshot[i] = orders[i];
            }
            return snapshot;
        }
    }

    private static class OrderRecord {
        final long orderId;
        final long[] quantities;
        volatile OrderStatus status;

        OrderRecord(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities.clone();
            this.status = status;
        }
    }

    private enum OrderStatus {
        RESERVED, SHIPPED, CANCELLED
    }
}
