@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private final Dispenser dispenser;
    private long funds;
    private final Object lock = new Object();
    private long transactionIdCounter;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = prices.clone();
        this.dispenser = dispenser;
        this.funds = 0;
        this.transactionIdCounter = 1;
    }

    public synchronized boolean restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            return false;
        }
        // Simulate stock management
        return true;
    }

    public synchronized boolean insertFunds(long amount) {
        if (amount < 0) {
            return false;
        }
        funds += amount;
        return true;
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid transaction ID or quantity array");
        }

        synchronized (lock) {
            if (checkReservations(transactionId)) {
                return false;
            }

            long totalCost = 0;
            for (int i = 0; i < prices.length; i++) {
                if (quantities[i] < 0) {
                    return false;
                }
                totalCost += (long) prices[i] * quantities[i];
            }

            if (totalCost > funds) {
                return false;
            }

            reserveResources(transactionId, quantities);
            totalCost = 0;

            try {
                if (!dispenser.dispense(transactionId, quantities.clone())) {
                    rollback(transactionId);
                    return false;
                }

                commitPurchases(quantities, totalCost);

            } catch (RuntimeException e) {
                rollback(transactionId);
                throw e;
            }

            return true;
        }
    }

    public synchronized boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }

        synchronized (lock) {
            if (!checkReservations(transactionId)) {
                return false;
            }

            rollback(transactionId);

            return true;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(prices, funds);
    }

    private boolean checkReservations(long transactionId) {
        // Simulate reservation checking
        return false;
    }

    private void reserveResources(long transactionId, int[] quantities) {
        // Simulate resource reservation
    }

    private void commitPurchases(int[] quantities, long totalCost) {
        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] > 0) {
                funds -= (long) prices[i] * quantities[i];
            }
        }
    }

    private void rollback(long transactionId) {
        // Simulate rollback
    }

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities) throws RuntimeException;
    }

    public static class Snapshot {
        private final int[] prices;
        private final long funds;

        public Snapshot(int[] prices, long funds) {
            this.prices = prices.clone();
            this.funds = funds;
        }

        public int[] getPrices() {
            return prices.clone();
        }

        public long getFunds() {
            return funds;
        }
    }
}
