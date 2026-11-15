import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private int[] stock;
    private long funds;
    private final Map<Long, Bundle> transactions;
    private final Dispenser dispenser;

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = Arrays.copyOf(prices, prices.length);
        this.stock = new int[prices.length];
        this.funds = 0;
        this.transactions = new HashMap<>();
        this.dispenser = dispenser;
    }

    public synchronized void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("Invalid input");
        }
        stock[product] += quantity;
    }

    public synchronized boolean insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Invalid input");
        }
        funds += amount;
        return true;
    }

    public synchronized boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || transactions.containsKey(transactionId)) {
            throw new IllegalArgumentException("Invalid input");
        }
        if (!validateBundle(quantities) || !reserveBundle(transactionId, quantities)) {
            return false;
        }
        boolean success = false;
        try {
            int[] finalQuantities = Arrays.copyOf(quantities, quantities.length);
            boolean dispenseSuccess = dispenser.dispense(transactionId, finalQuantities);
            if (dispenseSuccess) {
                success = true;
                transactions.remove(transactionId);
                updateFundsAndStock(quantities, false);
            } else {
                transactions.remove(transactionId);
                updateFundsAndStock(quantities, true);
            }
        } catch (RuntimeException e) {
            transactions.remove(transactionId);
            updateFundsAndStock(quantities, true);
            throw e;
        }
        return success;
    }

    public synchronized boolean cancel(long transactionId) {
        if (transactionId <= 0 || !transactions.containsKey(transactionId)) {
            throw new IllegalArgumentException("Invalid input");
        }
        transactions.remove(transactionId);
        updateFundsAndStock(transactions.get(transactionId).quantities, true);
        return true;
    }

    public synchronized Snapshot snapshot() {
        int[] stockSnapshot = Arrays.copyOf(stock, stock.length);
        return new Snapshot(funds, stockSnapshot);
    }

    private boolean validateBundle(int[] quantities) {
        for (int i = 0; i < prices.length; i++) {
            if (quantities[i] > stock[i]) {
                return false;
            }
        }
        long totalCost = 0;
        for (int i = 0; i < prices.length; i++) {
            totalCost += (long) quantities[i] * prices[i];
        }
        return totalCost <= funds;
    }

    private boolean reserveBundle(long transactionId, int[] quantities) {
        Bundle bundle = new Bundle(transactionId, quantities);
        transactions.put(transactionId, bundle);
        updateFundsAndStock(quantities, true);
        return true;
    }

    private void updateFundsAndStock(int[] quantities, boolean rollback) {
        long totalCost = 0;
        if (!rollback) {
            for (int i = 0; i < prices.length; i++) {
                totalCost += (long) quantities[i] * prices[i];
            }
            funds -= totalCost;
        } else {
            for (int i = 0; i < prices.length; i++) {
                stock[i] += quantities[i];
            }
        }
    }

    private static class Bundle {
        final long transactionId;
        final int[] quantities;

        Bundle(long transactionId, int[] quantities) {
            this.transactionId = transactionId;
            this.quantities = Arrays.copyOf(quantities, quantities.length);
        }
    }

    public static class Snapshot {
        final long funds;
        final int[] stock;

        Snapshot(long funds, int[] stock) {
            this.funds = funds;
            this.stock = Arrays.copyOf(stock, stock.length);
        }
    }
}
