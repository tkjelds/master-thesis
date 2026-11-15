```java
public class Warehouse {
    private final long[] available;
    private final long[] reserved;
    private final OrderRecord[] orders;
    private int nextOrderId = 1;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock cannot be null or empty");
        }
        available = stock.clone();
        reserved = new long[stock.length];
        orders = new OrderRecord[1024]; // Assuming a maximum of 1023 orders
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        synchronized (this) {
            if (!checkValidOrderId(orderId)) {
                throw new IllegalArgumentException("Invalid or duplicate order ID");
            }
            if (!checkValidQuantities(quantities)) {
                return false;
            }

            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > available[i]) {
                    return false;
                }
            }

            for (int i = 0; i < quantities.length; i++) {
                available[i] -= quantities[i];
                reserved[i] += quantities[i];
            }
            orders[orderId] = new OrderRecord(orderId, quantities, OrderStatus.RESERVED);
            return true;
        }
    }

    public boolean shipOrder(long orderId) {
        synchronized (this) {
            if (!checkValidOrderId(orderId)) {
                return false;
            }
            OrderRecord order = orders[orderId];
            if (order == null || !order.isReserved()) {
                return false;
            }

            for (int i = 0; i < order.quantities.length; i++) {
                available[i] += order.quantities[i];
                reserved[i] -= order.quantities[i];
            }
            orders[orderId].setStatus(OrderStatus.SHIPPED);
            return true;
        }
    }

    public boolean cancelOrder(long orderId) {
        synchronized (this) {
            if (!checkValidOrderId(orderId)) {
                return false;
            }
            OrderRecord order = orders[orderId];
            if (order == null || !order.isReserved()) {
                return false;
            }

            for (int i = 0; i < order.quantities.length; i++) {
                available[i] += order.quantities[i];
                reserved[i] -= order.quantities[i];
            }
            orders[orderId].setStatus(OrderStatus.CANCELLED);
            return true;
        }
    }

    public void restock(int product, long quantity) {
        synchronized (this) {
            if (product < 0 || product >= available.length) {
                throw new IllegalArgumentException("Invalid product");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Nonpositive restock quantity");
            }

            long newAvailable = available[product] + quantity;
            if (newAvailable < 0) {
                throw new ArithmeticException("Long overflow");
            }
            available[product] = newAvailable;
        }
    }

    public OrderRecord[] snapshot() {
        synchronized (this) {
            long[] availableCopy = available.clone();
            long[] reservedCopy = reserved.clone();
            OrderRecord[] snapshotOrders = new OrderRecord[nextOrderId];

            for (int i = 1; i < nextOrderId; i++) {
                if (orders[i] != null) {
                    snapshotOrders[i] = new OrderRecord(orders[i].orderId, orders[i].quantities, orders[i].status);
                }
            }

            return new OrderRecord[]{
                    new OrderRecord(-1, availableCopy, OrderStatus.AVAILABLE),
                    new OrderRecord(-2, reservedCopy, OrderStatus.RESERVED),
                    snapshotOrders
            };
        }
    }

    private boolean checkValidOrderId(long orderId) {
        return orderId > 0 && orderId < nextOrderId;
    }

    private boolean checkValidQuantities(long[] quantities) {
        if (quantities == null || quantities.length != available.length) {
            return false;
        }
        for (long quantity : quantities) {
            if (quantity < 0) {
                return false;
            }
        }
        return true;
    }

    private static class OrderRecord {
        long orderId;
        long[] quantities;
        OrderStatus status;

        public OrderRecord(long orderId, long[] quantities, OrderStatus status) {
            this.orderId = orderId;
            this.quantities = quantities.clone();
            this.status = status;
        }

        public boolean isReserved() {
            return status == OrderStatus.RESERVED;
        }

        public void setStatus(OrderStatus status) {
            this.status = status;
        }
    }

    private enum OrderStatus {
        AVAILABLE,
        RESERVED,
        SHIPPED,
        CANCELLED
    }
}
```
