@Warehouse.ThreadSafe
class Warehouse {
    @interface ThreadSafe {}

    private final long[] available;
    private final long[] reserved;
    private final Order[] orders;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Stock must be nonempty");
        }
        this.available = stock.clone();
        this.reserved = new long[stock.length];
        this.orders = new Order[stock.length];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0 || !isValidQuantities(quantities)) {
            throw new IllegalArgumentException("Invalid order ID or quantities");
        }
        synchronized (this) {
            if (orders[(int) orderId] != null) {
                throw new IllegalArgumentException("Duplicate order ID");
            }
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

            orders[(int) orderId] = new Order(orderId, statuses.RESERVED, quantities);
            return true;
        }
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0 || !isTerminal(orderId, statuses.SHIPPED)) {
            return false;
        }

        synchronized (this) {
            if (orders[(int) orderId] != null && orders[(int) orderId].status == statuses.RESERVED) {
                for (int i = 0; i < orders[(int) orderId].quantities.length; i++) {
                    reserved[i] -= orders[(int) orderId].quantities[i];
                }
                orders[(int) orderId].status = statuses.SHIPPED;
                return true;
            } else {
                return false;
            }
        }
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0 || !isTerminal(orderId, statuses.CANCELLED)) {
            return false;
        }

        synchronized (this) {
            if (orders[(int) orderId] != null && orders[(int) orderId].status == statuses.RESERVED) {
                for (int i = 0; i < orders[(int) orderId].quantities.length; i++) {
                    available[i] += orders[(int) orderId].quantities[i];
                }
                orders[(int) orderId].status = statuses.CANCELLED;
                return true;
            } else {
                return false;
            }
        }
    }

    public void restock(int product, long quantity) {
        if (product < 0 || quantity <= 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }

        synchronized (this) {
            if ((Long.MAX_VALUE - available[product] - reserved[product]) < quantity) {
                throw new ArithmeticException("Restock would cause overflow");
            }
            available[product] += quantity;
        }
    }

    public Order[] snapshot() {
        synchronized (this) {
            Order[] snapshot = new Order[orders.length];
            for (int i = 0; i < orders.length; i++) {
                if (orders[i] != null) {
                    snapshot[i] = new Order(orders[i].id, orders[i].status, orders[i].quantities.clone());
                }
            }
            return snapshot;
        }
    }

    private boolean isValidQuantities(long[] quantities) {
        for (long quantity : quantities) {
            if (quantity < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isTerminal(long orderId, statuses terminalStatus) {
        synchronized (this) {
            return orders[(int) orderId] != null && orders[(int) orderId].status == terminalStatus;
        }
    }

    private static class Order {
        final long id;
        statuses status;
        final long[] quantities;

        public Order(long id, statuses status, long[] quantities) {
            this.id = id;
            this.status = status;
            this.quantities = quantities;
        }
    }

    private enum statuses {
        RESERVED, SHIPPED, CANCELLED
    }
}
