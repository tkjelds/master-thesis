@Warehouse.ThreadSafe
public class Warehouse {
    @interface ThreadSafe {}

    private final long[] availableStock;
    private final long[] reservedStock;
    private final OrderRecord[] orders;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock cannot be null or empty");
        }
        availableStock = initialStock.clone();
        reservedStock = new long[initialStock.length];
        orders = new OrderRecord[1_000_000]; // Assuming max 1 million orders
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0 || quantities == null || quantities.length != availableStock.length) {
            throw new IllegalArgumentException("Invalid order ID or quantities");
        }
        synchronized (this) {
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > availableStock[i]) {
                    return false;
                }
            }
            for (int i = 0; i < quantities.length; i++) {
                availableStock[i] -= quantities[i];
                reservedStock[i] += quantities[i];
            }
            orders[orderId - 1] = new OrderRecord(orderId, quantities.clone(), OrderStatus.RESERVED);
            return true;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0 || orders[orderId - 1] == null) {
            return false;
        }
        synchronized (this) {
            OrderRecord order = orders[orderId - 1];
            if (order.status == OrderStatus.RESERVED) {
                order.status = OrderStatus.SHIPPED;
                for (int i = 0; i < order.quantities.length; i++) {
                    reservedStock[i] -= order.quantities[i];
                }
                return true;
            }
        }
        return false;
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0 || orders[orderId - 1] == null) {
            return false;
        }
        synchronized (this) {
            OrderRecord order = orders[orderId - 1];
            if (order.status == OrderStatus.RESERVED) {
                order.status = OrderStatus.CANCELLED;
                for (int i = 0; i < order.quantities.length; i++) {
                    availableStock[i] += order.quantities[i];
                }
                return true;
            }
        }
        return false;
    }

    public boolean restock(int product, long quantity) {
        if (product < 0 || quantity <= 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }
        synchronized (this) {
            long total = availableStock[product] + reservedStock[product] + quantity;
            if (total < 0) {
                throw new IllegalArgumentException("Overflow detected");
            }
            availableStock[product] += quantity;
        }
        return true;
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return new Snapshot(availableStock.clone(), reservedStock.clone());
        }
    }

    private static class OrderRecord {
        final long id;
        final long[] quantities;
        volatile OrderStatus status;

        OrderRecord(long id, long[] quantities, OrderStatus status) {
            this.id = id;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private static class Snapshot {
        final long[] availableStock;
        final long[] reservedStock;

        Snapshot(long[] availableStock, long[] reservedStock) {
            this.availableStock = availableStock;
            this.reservedStock = reservedStock;
        }
    }

    private enum OrderStatus {
        RESERVED, SHIPPED, CANCELLED
    }
}
