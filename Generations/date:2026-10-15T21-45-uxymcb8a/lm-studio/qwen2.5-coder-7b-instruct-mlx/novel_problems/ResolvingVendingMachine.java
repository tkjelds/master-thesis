@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private final long[] stock;
    private final long funds;
    private final Dispenser dispenser;
    private final boolean[][] reservations;
    private final int transactionIdCount = 1024;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = prices;
        this.dispenser = dispenser;
        this.stock = new long[prices.length];
        this.funds = 0;
        this.reservations = new boolean[transactionIdCount][prices.length];
    }

    public synchronized void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }
        stock[product] += quantity;
    }

    public synchronized boolean insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Invalid amount");
        }
        funds += amount;
        return true;
    }

    public synchronized boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || transactionId >= transactionIdCount) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        if (isTransactionReserved(transactionId)) {
            throw new IllegalStateException("Transaction already reserved");
        }

        if (!isValidBundle(quantities) || !hasSufficientFunds(transactionId, quantities)) {
            return false;
        }

        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] > stock[i]) {
                return false;
            }
        }

        reserveResources(transactionId, quantities);

        try {
            boolean dispensed = dispenser.dispense(transactionId, quantities);
            if (dispensed) {
                commitTransaction(transactionId);
            } else {
                rollbackTransaction(transactionId);
            }
        } catch (RuntimeException e) {
            rollbackTransaction(transactionId);
            throw e;
        }

        return true;
    }

    public synchronized boolean cancel(long transactionId) {
        if (transactionId <= 0 || transactionId >= transactionIdCount) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        if (!isTransactionReserved(transactionId)) {
            return false;
        }

        rollbackTransaction(transactionId);
        return true;
    }

    public synchronized Snapshot snapshot() {
        long[] availableStock = stock.clone();
        return new Snapshot(availableStock, funds, getTransactionStatuses());
    }

    private boolean hasSufficientFunds(long transactionId, int[] quantities) {
        long totalCost = 0;
        for (int i = 0; i < prices.length; i++) {
            totalCost += prices[i] * quantities[i];
        }
        return funds >= totalCost;
    }

    private boolean isValidBundle(int[] quantities) {
        for (int quantity : quantities) {
            if (quantity < 0) {
                return false;
            }
        }
        for (int quantity : quantities) {
            if (quantity > 0) {
                return true;
            }
        }
        throw new IllegalArgumentException("Invalid bundle");
    }

    private boolean isTransactionReserved(long transactionId) {
        return reservations[transactionId % transactionIdCount][0];
    }

    private void reserveResources(long transactionId, int[] quantities) {
        for (int i = 0; i < prices.length; i++) {
            try {
                stock[i] -= quantities[i];
                funds -= prices[i] * quantities[i];
            } catch (ArithmeticException e) {
                rollbackTransaction(transactionId);
                throw new ArithmeticException("Overflow during reservation");
            }
        }
        for (int i = 0; i < prices.length; i++) {
            reservations[transactionId % transactionIdCount][i] = true;
        }
    }

    private void commitTransaction(long transactionId) {
        for (int i = 0; i < prices.length; i++) {
            try {
                stock[i] -= reservations[transactionId % transactionIdCount][i];
                funds -= prices[i] * reservations[transactionId % transactionIdCount][i];
            } catch (ArithmeticException e) {
                rollbackTransaction(transactionId);
                throw new ArithmeticException("Overflow during commit");
            }
        }
    }

    private void rollbackTransaction(long transactionId) {
        for (int i = 0; i < prices.length; i++) {
            stock[i] += reservations[transactionId % transactionIdCount][i];
            funds += prices[i] * reservations[transactionId % transactionIdCount][i];
        }
        for (int i = 0; i < prices.length; i++) {
            reservations[transactionId % transactionIdCount][i] = false;
        }
    }

    private int[] getTransactionStatuses() {
        int[] statuses = new int[transactionIdCount];
        for (int i = 0; i < transactionIdCount; i++) {
            if (reservations[i][0]) {
                statuses[i] = 1; // Reserved
            } else {
                statuses[i] = 0; // Not reserved
            }
        }
        return statuses;
    }

    public interface Snapshot {
        long[] getAvailableStock();
        long getFunds();
        int[] getTransactionStatuses();
    }
}
