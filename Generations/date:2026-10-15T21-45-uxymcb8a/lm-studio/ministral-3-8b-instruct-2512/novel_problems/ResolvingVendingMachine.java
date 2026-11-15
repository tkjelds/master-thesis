@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private long funds = 0L;
    private int[] stock = new int[0];
    private final Dispenser dispenser;

    // Transaction state: 0=pending, 1=cancelled, 2=committed
    private final int[] transactionStates = new int[0];
    // Reserved quantities per product for each transaction
    private final int[][] reservedQuantities = new int[0][];
    // Reserved funds per transaction
    private final long[] reservedFunds = new long[0];
    // Transaction IDs and their indices
    private final long[] transactionIds = new long[0];
    // Next available transaction ID
    private volatile long nextTransactionId = 1;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = prices.clone();
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        synchronized (this) {
            if (product < 0 || product >= prices.length || quantity <= 0)
                throw new IllegalArgumentException("Invalid restock parameters");

            // Check for overflow with reserved resources
            long total = stock[product] + quantity;
            if (total < 0) throw new IllegalArgumentException("Overflow in restock");

            stock[product] = (int) total;
        }
    }

    public void insertFunds(long amount) {
        synchronized (this) {
            if (amount <= 0)
                throw new IllegalArgumentException("Invalid amount");

            // Check for overflow with reserved funds
            long total = funds + amount;
            if (total < 0) throw new IllegalArgumentException("Overflow in funds");

            funds = total;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null)
            throw new IllegalArgumentException("Invalid transaction ID or bundle");

        int[] validatedQuantities = validateBundle(quantities);
        if (validatedQuantities == null) return false;

        synchronized (this) {
            // Check for duplicate transaction ID
            if (nextTransactionId <= transactionId && containsTransactionId(transactionIds, transactionId))
                throw new IllegalArgumentException("Duplicate transaction ID");

            // Check if there's space for a new transaction
            int[] newTransactionStates = new int[transactionStates.length + 1];
            System.arraycopy(transactionStates, 0, newTransactionStates, 0, transactionStates.length);
            transactionStates = newTransactionStates;

            long[] newReservedFunds = new long[reservedFunds.length + 1];
            System.arraycopy(reservedFunds, 0, newReservedFunds, 0, reservedFunds.length);
            reservedFunds = newReservedFunds;

            int[][] newReservedQuantities = new int[reservedQuantities.length + 1][];
            System.arraycopy(reservedQuantities, 0, newReservedQuantities, 0, reservedQuantities.length);
            reservedQuantities = newReservedQuantities;

            long[] newTransactionIds = new long[transactionIds.length + 1];
            System.arraycopy(transactionIds, 0, newTransactionIds, 0, transactionIds.length);
            transactionIds = newTransactionIds;

            // Reserve resources
            long totalPrice = 0;
            boolean hasStock = true;
            for (int i = 0; i < validatedQuantities.length; i++) {
                if (validatedQuantities[i] > 0) {
                    totalPrice += prices[i] * validatedQuantities[i];
                    if (stock[i] < validatedQuantities[i]) {
                        hasStock = false;
                        break;
                    }
                }
            }

            if (!hasStock) {
                return false; // Not enough stock
            }

            if (totalPrice > funds) {
                return false; // Not enough funds
            }

            // Record reservation
            int index = transactionStates.length - 1;
            transactionStates[index] = 0; // Pending
            reservedFunds[index] = totalPrice;
            transactionIds[index] = transactionId;

            int[] reservedBundle = new int[prices.length];
            System.arraycopy(validatedQuantities, 0, reservedBundle, 0, validatedQuantities.length);
            reservedQuantities[index] = reservedBundle;

            // Reserve stock and funds
            for (int i = 0; i < validatedQuantities.length; i++) {
                if (validatedQuantities[i] > 0) {
                    stock[i] -= validatedQuantities[i];
                }
            }

            funds -= totalPrice;
        }

        // Attempt to dispense
        try {
            boolean success = dispenser.dispense(transactionId, validatedQuantities.clone());
            if (success) {
                synchronized (this) {
                    // Commit transaction
                    int index = findTransactionIndex(transactionId);
                    if (index >= 0 && transactionStates[index] == 0) {
                        transactionStates[index] = 2; // Committed
                    }
                }
                return true;
            } else {
                synchronized (this) {
                    // Rollback
                    rollbackTransaction(transactionId);
                }
                return false;
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                // Rollback
                rollbackTransaction(transactionId);
            }
            throw e;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (this) {
            int index = findTransactionIndex(transactionId);
            if (index >= 0 && transactionStates[index] == 0) {
                // Rollback
                rollbackTransaction(transactionId);
                return true;
            }
            return false;
        }
    }

    public Object snapshot() {
        synchronized (this) {
            // Create a copy of the current state
            int[] snapshotStock = stock.clone();
            long snapshotFunds = funds;

            // Create copies of transaction states and IDs
            int[] snapshotStates = new int[transactionStates.length];
            System.arraycopy(transactionStates, 0, snapshotStates, 0, transactionStates.length);

            long[] snapshotIds = new long[transactionIds.length];
            System.arraycopy(transactionIds, 0, snapshotIds, 0, transactionIds.length);

            // Create copies of reserved quantities and funds
            int[][] snapshotReservedQuantities = new int[reservedQuantities.length][];
            for (int i = 0; *//* Reserved quantities */*; i++) {
                if (reservedQuantities[i] != null) {
                    snapshotReservedQuantities[i] = reservedQuantities[i].clone();
                }
            }

            long[] snapshotReservedFunds = new long[reservedFunds.length];
            System.arraycopy(reservedFunds, 0, snapshotReservedFunds, 0, reservedFunds.length);

            return new Object() {
                public int[] getStock() { return snapshotStock; }
                public long getFunds() { return snapshotFunds; }
                public int[] getTransactionStates() { return snapshotStates; }
                public long[] getTransactionIds() { return snapshotIds; }
                public int[][] getReservedQuantities() { return snapshotReservedQuantities; }
                public long[] getReservedFunds() { return snapshotReservedFunds; }
            };
        }
    }

    private int[] validateBundle(int[] quantities) {
        if (quantities.length != prices.length)
            throw new IllegalArgumentException("Bundle size mismatch");

        int total = 0;
        for (int q : quantities) {
            if (q < 0)
                throw new IllegalArgumentException("Negative quantity");
            total += q;
        }

        if (total == 0)
            throw new IllegalArgumentException("No positive quantity");

        return quantities;
    }

    private boolean containsTransactionId(long[] ids, long id) {
        for (long transactionId : ids) {
            if (transactionId == id)
                return true;
        }
        return false;
    }

    private int findTransactionIndex(long transactionId) {
        for (int i = 0; i < transactionIds.length; i++) {
            if (transactionIds[i] == transactionId)
                return i;
        }
        return -1;
    }

    private void rollbackTransaction(long transactionId) {
        int index = findTransactionIndex(transactionId);
        if (index >= 0 && transactionStates[index] == 0) {
            // Return reserved funds
            funds += reservedFunds[index];

            // Return reserved stock
            int[] bundle = reservedQuantities[index];
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > 0) {
                    stock[i] += bundle[i];
                }
            }

            // Clear reservation
            transactionStates[index] = 1; // Cancelled
        }
    }

    private interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }
}
