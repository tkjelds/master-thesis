@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private long funds;
    private int[] stock;
    private final Object lock = new Object();
    private int nextTransactionId = 1;
    private final Transaction[] transactions;

    public ResolvingVendingMachine(int[] prices) {
        this.prices = prices;
        this.stock = new int[prices.length];
        this.transactions = new Transaction[1024]; // Fixed size for simplicity
    }

    public synchronized void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("Invalid product");
        }
        if (quantity < 0) {
            throw new ArithmeticException("Negative quantity");
        }

        synchronized (lock) {
            stock[product] += quantity;
            lock.notifyAll();
        }
    }

    public synchronized boolean insertFunds(long amount) {
        if (amount < 0) {
            throw new ArithmeticException("Negative funds");
        }

        synchronized (lock) {
            funds += amount;
            lock.notifyAll();
            return true;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || transactions[transactionId - 1] != null) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        if (quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid quantity array length");
        }

        synchronized (lock) {
            if (!isValidBundle(quantities)) {
                return false;
            }

            long totalCost = calculateTotalCost(quantities);
            if (totalCost > funds) {
                return false;
            }

            transactions[transactionId - 1] = new Transaction(transactionId, quantities);
            funds -= totalCost;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            if (transactionId <= 0 || transactions[transactionId - 1] == null) {
                return false;
            }

            Transaction transaction = transactions[transactionId - 1];
            for (int i = 0; i < prices.length; i++) {
                stock[i] += transaction.quantities[i];
            }
            funds += calculateTotalCost(transaction.quantities);
            transactions[transactionId - 1] = null;
            return true;
        }
    }

    public synchronized Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(prices, stock, funds, transactions);
        }
    }

    private boolean isValidBundle(int[] quantities) {
        for (int quantity : quantities) {
            if (quantity < 0) {
                return false;
            }
        }

        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] > stock[i]) {
                return false;
            }
        }

        return true;
    }

    private long calculateTotalCost(int[] quantities) {
        long total = 0;
        for (int i = 0; i < prices.length; i++) {
            total += (long) prices[i] * quantities[i];
        }

        return total;
    }

    private static class Transaction {
        final long transactionId;
        final int[] quantities;

        Transaction(long transactionId, int[] quantities) {
            this.transactionId = transactionId;
            this.quantities = quantities.clone();
        }
    }

    public static class Snapshot {
        final int[] prices;
        final int[] stock;
        final long funds;
        final Transaction[] transactions;

        Snapshot(int[] prices, int[] stock, long funds, Transaction[] transactions) {
            this.prices = prices.clone();
            this.stock = stock.clone();
            this.funds = funds;
            this.transactions = transactions.clone();
        }
    }

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }
}
