@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING, COMMITTED, CANCELLED, FAILED
    }

    public static final class TransactionSnapshot {
        public final long transactionId;
        public final Status status;

        private TransactionSnapshot(long transactionId, Status status) {
            this.transactionId = transactionId;
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final TransactionSnapshot[] transactions;

        private Snapshot(
                int[] availableStock,
                long availableFunds,
                TransactionSnapshot[] transactions) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactions = transactions;
        }
    }

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long price;
        final Transaction next;
        Status status;

        Transaction(
                long id,
                int[] quantities,
                long price,
                Status status,
                Transaction next) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private Transaction transactions;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser are required");
        }

        int[] copiedPrices = copy(prices);
        for (int i = 0; i < copiedPrices.length; i++) {
            if (copiedPrices[i] < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.prices = copiedPrices;
        this.dispenser = dispenser;
        this.availableStock = new int[copiedPrices.length];
        this.reservedStock = new int[copiedPrices.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("Invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be nonnegative");
        }

        synchronized (monitor) {
            // Reserve enough capacity for every pending rollback.
            if (quantity > Integer.MAX_VALUE
                    - availableStock[product] - reservedStock[product]) {
                throw new ArithmeticException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (monitor) {
            if (amount > Long.MAX_VALUE - availableFunds - reservedFunds) {
                throw new ArithmeticException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    /**
     * Resource shortages return false without waiting. The dispenser runs
     * synchronously outside the monitor. Invalid requests do not consume IDs;
     * registered IDs remain consumed regardless of their terminal status.
     */
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        requirePositiveId(transactionId);
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("One quantity per product is required");
        }

        int[] bundle = copy(quantities);
        boolean nonempty = false;
        long totalPrice = 0;

        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            if (quantity > 0) {
                nonempty = true;
            }

            // Both factors are nonnegative ints, so their product fits in long.
            long itemPrice = (long) prices[i] * quantity;
            if (itemPrice > Long.MAX_VALUE - totalPrice) {
                throw new ArithmeticException("Bundle price overflow");
            }
            totalPrice += itemPrice;
        }

        if (!nonempty) {
            throw new IllegalArgumentException("Bundle must be nonempty");
        }

        int[] dispenserBundle = copy(bundle);
        Transaction transaction;

        synchronized (monitor) {
            if (find(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID already used");
            }
            if (transactionCount == Integer.MAX_VALUE) {
                throw new ArithmeticException("Transaction count overflow");
            }

            boolean available = totalPrice <= availableFunds;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }

            transaction = new Transaction(
                    transactionId,
                    bundle,
                    totalPrice,
                    available ? Status.PENDING : Status.FAILED,
                    transactions);

            transactions = transaction;
            transactionCount++;

            if (!available) {
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= totalPrice;
            reservedFunds += totalPrice;
        }

        final boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, dispenserBundle);
        } catch (RuntimeException | Error failure) {
            resolve(transaction, false);
            throw failure;
        }

        return resolve(transaction, dispensed);
    }

    public boolean cancel(long transactionId) {
        requirePositiveId(transactionId);

        synchronized (monitor) {
            Transaction transaction = find(transactionId);
            if (transaction == null || transaction.status != Status.PENDING) {
                return false;
            }

            finishPending(transaction, Status.CANCELLED);
            return true;
        }
    }

    /**
     * Returns detached arrays, with transactions ordered by registration.
     */
    public Snapshot snapshot() {
        synchronized (monitor) {
            int[] stock = copy(availableStock);
            TransactionSnapshot[] states =
                    new TransactionSnapshot[transactionCount];

            int index = transactionCount;
            for (Transaction transaction = transactions;
                    transaction != null;
                    transaction = transaction.next) {
                states[--index] = new TransactionSnapshot(
                        transaction.id, transaction.status);
            }

            return new Snapshot(stock, availableFunds, states);
        }
    }

    private boolean resolve(Transaction transaction, boolean dispensed) {
        synchronized (monitor) {
            if (transaction.status != Status.PENDING) {
                return false;
            }

            finishPending(
                    transaction, dispensed ? Status.COMMITTED : Status.FAILED);
            return dispensed;
        }
    }

    // Called only while holding monitor and only for a pending transaction.
    private void finishPending(Transaction transaction, Status terminalStatus) {
        boolean refund = terminalStatus != Status.COMMITTED;

        for (int i = 0; i < availableStock.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            if (refund) {
                availableStock[i] += quantity;
            }
        }

        reservedFunds -= transaction.price;
        if (refund) {
            availableFunds += transaction.price;
        }

        transaction.status = terminalStatus;
    }

    // Called only while holding monitor.
    private Transaction find(long transactionId) {
        for (Transaction transaction = transactions;
                transaction != null;
                transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    private static void requirePositiveId(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
    }

    private static int[] copy(int[] source) {
        int[] result = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
