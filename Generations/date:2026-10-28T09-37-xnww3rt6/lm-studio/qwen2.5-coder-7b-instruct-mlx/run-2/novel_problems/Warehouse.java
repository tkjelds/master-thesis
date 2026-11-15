@Warehouse.ThreadSafe
public class Warehouse {
    @interface ThreadSafe {}

    private final long[] available;
    private final long[] reserved;
    private final Order[] orders;
    private int nextOrderId = 1;

    public Warehouse(long[] initialStock) {
        if (initialStock == null || initialStock.length == 0) {
            throw new IllegalArgumentException("Initial stock must be nonempty");
        }
        this.available = initialStock.clone();
        this.reserved = new long[initialStock.length];
        this.orders = new Order[initialStock.length * 2]; // Assuming a reasonable upper bound for orders
    }

    public synchronized void restock(int product, long quantity) {
        if (product < 0 || product >= available.length || quantity <= 0) {
            throw new IllegalArgumentException("Invalid restock parameters");
        }
        long sum = available[product] + reserved[product] + quantity;
        if (sum < 0) {
            throw new ArithmeticException("Long overflow detected during restock");
        }
        available[product] += quantity;
    }

    public synchronized boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0 || orders[orderId - 1] != null) {
            throw new IllegalArgumentException("Invalid order ID or duplicate ID");
        }
        if (quantities == null || quantities.length != available.length) {
            throw new IllegalArgumentException("Invalid bundle size");
        }
        boolean canReserve = true;
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] > available[i]) {
                canReserve = false;
                break;
            }
        }
        if (canReserve) {
            for (int i = 0; i < quantities.length; i++) {
                available[i] -= quantities[i];
                reserved[i] += quantities[i];
            }
            orders[orderId - 1] = new Order(orderId, quantities, Status.RESERVED);
            return true;
        } else {
            return false;
        }
    }

    public synchronized boolean shipOrder(long orderId) {
        Order order = orders[orderId - 1];
        if (order == null || order.status != Status.RESERVED) {
            return false;
        }
        for (int i = 0; i < order.quantities.length; i++) {
            reserved[i] -= order.quantities[i];
        }
        order.status = Status.SHIPPED;
        return true;
    }

    public synchronized boolean cancelOrder(long orderId) {
        Order order = orders[orderId - 1];
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

    public synchronized Snapshot snapshot() {
        long[] availableCopy = available.clone();
        long[] reservedCopy = reserved.clone();
        Order[] ordersCopy = new Order[orders.length];
        for (int i = 0; i < orders.length; i++) {
            if (orders[i] != null) {
                ordersCopy[i] = new Order(orders[i].orderId, orders[i].quantities.clone(), orders[i].status);
            }
        }
        return new Snapshot(availableCopy, reservedCopy, ordersCopy);
    }

    private static class Order {
        final long orderId;
        final long[] quantities;
        Status status;

        Order(long orderId, long[] quantities, Status status) {
            this.orderId = orderId;
            this.quantities = quantities;
            this.status = status;
        }
    }

    private enum Status {
        RESERVED, SHIPPED, CANCELLED
    }

    public static class Snapshot {
        final long[] available;
        final long[] reserved;
        final Order[] orders;

        Snapshot(long[] available, long[] reserved, Order[] orders) {
            this.available = available;
            this.reserved = reserved;
            this.orders = orders;
        }
    }
}
