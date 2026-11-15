```java
import java.util.Arrays;

public class ResolvingVendingMachine {
    private final int[] prices;
    private final int[][] bundles;
    private long funds;
    private final boolean[][] reservations;
    private final Object lock;

    public ResolvingVendingMachine(int[] prices, int[][] bundles) {
        this.prices = Arrays.copyOf(prices, prices.length);
        this.bundles = Arrays.copyOf(bundles, bundles.length);
        this.funds = 0;
        this.reservations = new boolean[bundles.length][prices.length];
        this.lock = new Object();
    }

    public boolean restock(int product, int quantity) {
        synchronized (lock) {
            if (product < 0 || product >= prices.length) {
                return false;
            }
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i][product] && quantity > 0) {
                    return false;
                }
            }
            funds += product * quantity;
            return true;
        }
    }

    public boolean insertFunds(long amount) {
        synchronized (lock) {
            if (amount <= 0) {
                return false;
            }
            funds += amount;
            return true;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        synchronized (lock) {
            if (transactionId <= 0 || Arrays.stream(quantities).anyMatch(q -> q < 0)) {
                throw new IllegalArgumentException("Invalid transaction ID or quantities");
            }
            if (Arrays.stream(quantities).anyMatch(q -> q > bundles.length)) {
                return false;
            }
            if (Arrays.stream(quantities).anyMatch(q -> q > 0 && reservations[transactionId - 1][q - 1])) {
                return false;
            }
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > bundles[transactionId - 1][i]) {
                    return false;
                }
            }
            long total = Arrays.stream(quantities).asLongStream().map(i -> (long) i * prices[i]).sum();
            if (total > funds) {
                return false;
            }
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    funds -= quantities[i] * prices[i];
                    reservations[transactionId - 1][i] = true;
                }
            }
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            if (transactionId <= 0 || transactionsDone(transactionId)) {
                return false;
            }
            for (int i = 0; i < bundles[transactionId - 1].length; i++) {
                if (reservations[transactionId - 1][i]) {
                    funds += bundles[transactionId - 1][i] * prices[i];
                    reservations[transactionId - 1][i] = false;
                }
            }
            return true;
        }
    }

    public boolean snapshot(int[] availableStock, long availableFunds) {
        synchronized (lock) {
            Arrays.fill(availableStock, 0);
            for (int i = 0; i < bundles.length; i++) {
                availableFunds -= funds;
                for (int j = 0; j < bundles[i].length; j++) {
                    availableStock[j] += bundles[i][j];
                }
            }
        }
        return true;
    }

    private boolean transactionsDone(long transactionId) {
        synchronized (lock) {
            for (int i = 0; i < bundles.length; i++) {
                if (!reservations[transactionId - 1][i]) {
                    return false;
                }
            }
        }
        return true;
    }

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }
}
```
