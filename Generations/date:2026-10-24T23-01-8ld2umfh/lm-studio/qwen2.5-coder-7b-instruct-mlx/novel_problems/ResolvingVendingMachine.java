@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private long funds;
    private final TransactionManager transactionManager;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = prices.clone();
        this.funds = 0;
        this.transactionManager = new TransactionManager(dispenser);
    }

    public synchronized boolean restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            return false;
        }
        transactionManager.restock(product, quantity);
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
        if (transactionId <= 0 || quantities == null) {
            throw new IllegalArgumentException("Invalid transaction ID or quantities");
        }
        return transactionManager.purchaseBundle(transactionId, quantities);
    }

    public synchronized boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        return transactionManager.cancel(transactionId);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(prices, funds, transactionManager.getSnapshot());
    }

    public static class Snapshot {
        private final int[] prices;
        private final long funds;
        private final TransactionManager.Snapshot transactionSnapshot;

        public Snapshot(int[] prices, long funds, TransactionManager.Snapshot transactionSnapshot) {
            this.prices = prices.clone();
            this.funds = funds;
            this.transactionSnapshot = transactionSnapshot;
        }

        public int[] getPrices() {
            return prices.clone();
        }

        public long getFunds() {
            return funds;
        }

        public TransactionManager.Snapshot getTransactionSnapshot() {
            return transactionSnapshot;
        }
    }
}

interface Dispenser {
    boolean dispense(long transactionId, int[] quantities);
}

@TransactionManager.ThreadSafe
class TransactionManager {
    @interface ThreadSafe {}

    private final Dispenser dispenser;
    private final long[] reservedFunds;
    private final int[][] reservedQuantities;
    private final boolean[] activeTransactions;

    public TransactionManager(Dispenser dispenser) {
        this.dispenser = dispenser;
        reservedFunds = new long[1000]; // Assuming max 1000 transactions
        reservedQuantities = new int[1000][];
        activeTransactions = new boolean[1000];
    }

    public synchronized void restock(int product, int quantity) {
        for (int i = 0; i < activeTransactions.length; i++) {
            if (activeTransactions[i] && reservedQuantities[i][product] > 0) {
                reserveFunds(i, -prices[product] * reservedQuantities[i][product]);
            }
        }
        // Actual restock logic would be here
    }

    public synchronized boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId < 1 || transactionId >= activeTransactions.length || activeTransactions[(int) transactionId]) {
            throw new IllegalArgumentException("Invalid or already used transaction ID");
        }
        if (quantities.length != prices.length) {
            return false;
        }

        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] < 0) {
                return false;
            }
        }

        int totalCost = 0;
        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] * prices[i] / 1000 > Integer.MAX_VALUE - totalCost) {
                return false;
            }
            totalCost += quantities[i] * prices[i];
        }

        if (totalCost > funds) {
            return false;
        }

        reserveFunds((int) transactionId, -totalCost);
        for (int i = 0; i < prices.length; i++) {
            reservedQuantities[(int) transactionId][i] = quantities[i];
        }
        activeTransactions[(int) transactionId] = true;

        boolean dispensed = false;
        try {
            dispensed = dispenser.dispense(transactionId, quantities);
        } catch (RuntimeException e) {
            cancel(transactionId);
            throw e;
        }

        if (!dispensed) {
            cancel(transactionId);
        } else {
            deductFunds((int) transactionId, totalCost);
            clearReservations((int) transactionId);
        }

        return dispensed;
    }

    public synchronized boolean cancel(long transactionId) {
        if (transactionId < 1 || transactionId >= activeTransactions.length || !activeTransactions[(int) transactionId]) {
            return false;
        }

        clearReservations((int) transactionId);
        activeTransactions[(int) transactionId] = false;
        return true;
    }

    private synchronized void reserveFunds(int transactionId, long amount) {
        if (amount < 0) {
            funds -= amount;
        } else {
            reservedFunds[transactionId] += amount;
        }
    }

    private synchronized void deductFunds(int transactionId, long amount) {
        funds -= amount;
    }

    private synchronized void clearReservations(int transactionId) {
        reservedFunds[transactionId] = 0;
        for (int i = 0; i < prices.length; i++) {
            reservedQuantities[transactionId][i] = 0;
        }
    }

    public synchronized Snapshot getSnapshot() {
        return new Snapshot(reservedFunds, reservedQuantities, activeTransactions);
    }

    public static class Snapshot {
        private final long[] reservedFunds;
        private final int[][] reservedQuantities;
        private final boolean[] activeTransactions;

        public Snapshot(long[] reservedFunds, int[][] reservedQuantities, boolean[] activeTransactions) {
            this.reservedFunds = reservedFunds.clone();
            this.reservedQuantities = reservedQuantities.clone();
            this.activeTransactions = activeTransactions.clone();
        }

        public long[] getReservedFunds() {
            return reservedFunds.clone();
        }

        public int[][] getReservedQuantities() {
            return reservedQuantities.clone();
        }

        public boolean[] getActiveTransactions() {
            return activeTransactions.clone();
        }
    }
}
