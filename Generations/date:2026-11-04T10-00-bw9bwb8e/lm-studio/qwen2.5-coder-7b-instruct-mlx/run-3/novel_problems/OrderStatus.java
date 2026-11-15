@Warehouse.ThreadSafe
class Warehouse {
    @interface ThreadSafe {}

    private final long[] available;
    private final long[] reserved;
    private final OrderRecord[] orders;

    public Warehouse(long[] stock) {
        this.available = stock.clone();
        this.reserved = new long[stock.length];
        this.orders = new OrderRecord[(int) (10 * stock.length)];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0 || containsOrderId(orderId)) {
            throw new IllegalArgumentException("Invalid order ID or duplicate ID");
        }
        synchronized (this) {
            if (!checkQuantities(quantities)) {
                return false;
            }
        }

        long[] copyOfQuantities = quantities.clone();
        synchronized (this) {
            if (!checkQuantities(copyOfQuantities)) {
                return false;
            }
            for (int i = 0; i < copyOfQuantities.length; i++) {
                available[i] -= copyOfQuantities[i];
                reserved[i] += copyOfQuantities[i];
            }
        }
        recordOrder(orderId, copyOfQuantities, OrderStatus.RESERVED);
        return true;
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0 || !containsOrderId(orderId)) {
            return false;
        }
        OrderRecord order = orders[(int) orderId - 1];
        if (order.status != OrderStatus.RESERVED) {
            return false;
        }
        synchronized (this) {
            for (int i = 0; i < order.quantities.length; i++) {
                available[i] += order.quantities[i];
                reserved[i] -= order.quantities[i];
            }
        }
        recordOrder(orderId, order.quantities, OrderStatus.SHIPPED);
        return true;
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0 || !containsOrderId(orderId)) {
            return false;
        }
        OrderRecord order = orders[(int) orderId - 1];
        if (order.status != OrderStatus.RESERVED) {
            return false;
        }
        synchronized (this) {
            for (int i = 0; i < order.quantities.length; i++) {
                available[i] += order.quantities[i];
                reserved[i] -= order.quantities[i];
            }
        }
        recordOrder(orderId, order.quantities, OrderStatus.CANCELLED);
        return true;
    }

    public boolean restock(int product, long quantity) {
        if (product < 0 || product >= available.length || quantity <= 0) {
            return false;
        }
        synchronized (this) {
            if ((available[product] + reserved[product] + quantity) < 0) {
                return false;
            }
        }

        available[product] += quantity;

        return true;
    }

    public OrderRecord[] snapshot() {
        synchronized (this) {
            OrderRecord[] snapshot = new OrderRecord[orders.length];
            for (int i = 0; i < orders.length; i++) {
                snapshot[i] = new OrderRecord(orders[i].orderId, orders[i].quantities.clone(), orders[i].status);
            }
            return snapshot;
        }
    }

    private boolean checkQuantities(long[] quantities) {
        for (long quantity : quantities) {
            if (quantity < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean containsOrderId(long orderId) {
        for (int i = 0; i < orders.length; i++) {
            if (orders[i] != null && orders[i].orderId == orderId) {
                return true;
            }
        }
        return false;
    }

    private void recordOrder(long orderId, long[] quantities, OrderStatus status) {
        int index = (int) orderId - 1;
        if (index >= orders.length || orders[index] != null && orders[index].orderId == orderId) {
            return;
        }
        orders[index] = new OrderRecord(orderId, quantities, status);
    }

    private static class OrderRecord {
        long orderId;
        long[] quantities;
        OrderStatus status;

        public OrderRecord(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    public enum OrderStatus {
        RESERVED, SHIPPED, CANCELLED
    }
}
