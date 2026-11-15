@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private long funds;
    private final ReservationRecord[] reservations;
    private int nextTransactionId;

    public ResolvingVendingMachine(int[] prices) {
        this.prices = prices.clone();
        this.funds = 0;
        this.reservations = new ReservationRecord[1024]; // Arbitrary size
        this.nextTransactionId = 1;
    }

    public synchronized void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity <= 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }
        if (quantity > Integer.MAX_VALUE - prices[product]) {
            throw new ArithmeticException("Arithmetic overflow");
        }
        // Simulate restocking
    }

    public synchronized boolean insertFunds(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Invalid amount");
        }
        funds += amount;
        return true;
    }

    public synchronized boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || reservations[transactionId - 1] != null) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        if (validateQuantities(quantities)) {
            return false;
        }

        ReservationRecord record = new ReservationRecord(transactionId, quantities);
        reservations[transactionId - 1] = record;

        if (!record.reserve()) {
            return false;
        }

        funds -= record.getTotalPrice(prices);
        return true;
    }

    public synchronized boolean cancel(long transactionId) {
        if (transactionId <= 0 || reservations[transactionId - 1] == null) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        ReservationRecord record = reservations[transactionId - 1];
        if (!record.cancel()) {
            return false;
        }
        reservations[transactionId - 1] = null;
        record.unreserve(prices);
        return true;
    }

    public synchronized Snapshot snapshot() {
        // Clone prices, funds and reservations
        return new Snapshot(prices.clone(), funds, reservations);
    }

    private boolean validateQuantities(int[] quantities) {
        for (int quantity : quantities) {
            if (quantity < 0) {
                return true;
            }
        }
        return false;
    }

    private static class ReservationRecord {
        private final long transactionId;
        private final int[] quantities;
        private boolean isReserved;

        public ReservationRecord(long transactionId, int[] quantities) {
            this.transactionId = transactionId;
            this.quantities = quantities.clone();
        }

        public boolean reserve() {
            synchronized (this) {
                if (isReserved) {
                    return false;
                }
                isReserved = true;
                return true;
            }
        }

        public boolean cancel() {
            synchronized (this) {
                if (!isReserved) {
                    return false;
                }
                isReserved = false;
                return true;
            }
        }

        public long getTotalPrice(int[] prices) {
            long total = 0;
            for (int i = 0; i < quantities.length; i++) {
                total += (long) prices[i] * quantities[i];
            }
            return total;
        }

        public void unreserve(int[] prices) {
            synchronized (this) {
                if (!isReserved) {
                    return;
                }
                for (int i = 0; i < quantities.length; i++) {
                    // Simulate unreserving
                }
                isReserved = false;
            }
        }
    }

    public static class Snapshot {
        private final int[] prices;
        private final long funds;
        private final ReservationRecord[] reservations;

        public Snapshot(int[] prices, long funds, ReservationRecord[] reservations) {
            this.prices = prices;
            this.funds = funds;
            this.reservations = reservations.clone();
        }

        // Getters for prices, funds and reservations
    }
}
