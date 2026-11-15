@Warehouse.ThreadSafe
class Warehouse {
    @interface ThreadSafe {}

    private long[] available;
    private long[] reserved;
    private OrderRecord[] orders;

    public Warehouse(long[] stock) {
        if (stock == null || stock.length == 0) {
            throw new IllegalArgumentException("Invalid stock array");
        }
        available = stock.clone();
        reserved = new long[stock.length];
        orders = new OrderRecord[0];
    }

    public boolean reserveOrder(long orderId, long[] quantities) {
        if (orderId <= 0 || orders.length > 0 && Arrays.stream(orders).anyMatch(order -> order.id == orderId)) {
            throw new IllegalArgumentException("Invalid or duplicate order ID");
        }
        if (!checkQuantities(quantities)) {
            return false;
        }

        synchronized (this) {
            if (reserveQuantities(quantities)) {
                long[] bundle = quantities.clone();
                orders = Arrays.copyOf(orders, orders.length + 1);
                orders[orders.length - 1] = new OrderRecord(orderId, bundle, Status.RESERVED);
                return true;
            }
        }
        return false;
    }

    public boolean shipOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Invalid order ID");
        }

        synchronized (this) {
            int index = findOrder(orderId);
            if (index != -1 && orders[index].status == Status.RESERVED) {
                long[] bundle = orders[index].bundle;
                consumeQuantities(bundle);
                orders[index].status = Status.SHIPPED;
                return true;
            }
        }
        return false;
    }

    public boolean cancelOrder(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Invalid order ID");
        }

        synchronized (this) {
            int index = findOrder(orderId);
            if (index != -1 && orders[index].status == Status.RESERVED) {
                restoreQuantities(orders[index].bundle);
                orders[index].status = Status.CANCELLED;
                return true;
            }
        }
        return false;
    }

    public void restock(int product, long quantity) {
        if (product < 0 || quantity <= 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }

        if (available.length <= product) {
            throw new IllegalArgumentException("Invalid product index");
        }

        long total = available[product] + reserved[product] + quantity;
        if (total < 0) {
            throw new ArithmeticException("Long overflow detected");
        }

        synchronized (this) {
            available[product] += quantity;
        }
    }

    public OrderRecord[] snapshot() {
        synchronized (this) {
            return orders.clone();
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

    private boolean reserveQuantities(long[] quantities) {
        for (int i = 0; i < quantities.length; i++) {
            if (available[i] < quantities[i]) {
                return false;
            }
        }

        for (int i = 0; i < quantities.length; i++) {
            available[i] -= quantities[i];
        }

        return true;
    }

    private void consumeQuantities(long[] bundle) {
        for (int i = 0; i < bundle.length; i++) {
            reserved[i] -= bundle[i];
        }
    }

    private void restoreQuantities(long[] quantities) {
        for (int i = 0; i < quantities.length; i++) {
            available[i] += quantities[i];
        }
    }

    private int findOrder(long orderId) {
        for (int i = 0; i < orders.length; i++) {
            if (orders[i].id == orderId) {
                return i;
            }
        }
        return -1;
    }

    private static class OrderRecord {
        final long id;
        final long[] bundle;
        Status status;

        OrderRecord(long id, long[] bundle, Status status) {
            this.id = id;
            this.bundle = bundle;
            this.status = status;
        }
    }

    enum Status {
        RESERVED, SHIPPED, CANCELLED
    }
}
