@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING,
        COMMITTED,
        CANCELLED,
        FAILED,
        UNAVAILABLE
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
        public final long[] availableStock;
        public final long availableFunds;
        public final TransactionSnapshot[] transactions;

        private Snapshot(
                long[] availableStock,
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
        Status status;
        Transaction next;

        Transaction(long id, int[] quantities, long price, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final Dispenser dispenser;
    private final long[] availableStock;
    private final long[] reservedStock;

    private long availableFunds;
    private long reservedFunds;
    private Transaction transactions;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser are required");
        }

        int[] copiedPrices = prices.clone();
        for (int i = 0; i < copiedPrices.length; i++) {
            if (copiedPrices[i] < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.prices = copiedPrices;
        this.dispenser = dispenser;
        this.availableStock = new long[copiedPrices.length];
        this.reservedStock = new long[copiedPrices.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }

        synchronized (monitor) {
            long total = availableStock[product] + reservedStock[product];
            if ((long) quantity > Long.MAX_VALUE - total) {
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
            long total = availableFunds + reservedFunds;
            if (amount > Long.MAX_VALUE - total) {
                throw new ArithmeticException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    /*
     * Purchases do not wait for resources: an unavailable bundle returns false.
     * The dispenser is called without holding the state monitor.
     */
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null
                || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid transaction or bundle");
        }

        int[] bundle = quantities.clone();
        boolean hasPositiveQuantity = false;
        long price = 0;

        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }

            long itemPrice = (long) prices[i] * quantity;
            if (itemPrice > Long.MAX_VALUE - price) {
                throw new ArithmeticException("Bundle price overflow");
            }
            price += itemPrice;
        }

        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must not be empty");
        }

        int[] dispenserBundle = bundle.clone();
        Transaction transaction;

        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID already used");
            }

            boolean available = availableFunds >= price;
            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            transaction = new Transaction(
                    transactionId, bundle, price,
                    available ? Status.PENDING : Status.UNAVAILABLE);
            transaction.next = transactions;
            transactions = transaction;

            if (!available) {
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= price;
            reservedFunds += price;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, dispenserBundle);
        } catch (RuntimeException exception) {
            failIfPending(transaction);
            throw exception;
        } catch (Error error) {
            failIfPending(transaction);
            throw error;
        }

        synchronized (monitor) {
            if (transaction.status != Status.PENDING) {
                return false;
            }

            if (!dispensed) {
                rollback(transaction, Status.FAILED);
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                reservedStock[i] -= bundle[i];
            }
            reservedFunds -= price;
            transaction.status = Status.COMMITTED;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }

        synchronized (monitor) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != Status.PENDING) {
                return false;
            }

            rollback(transaction, Status.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int count = 0;
            for (Transaction transaction = transactions;
                    transaction != null;
                    transaction = transaction.next) {
                count++;
            }

            TransactionSnapshot[] copies = new TransactionSnapshot[count];
            int index = 0;
            for (Transaction transaction = transactions;
                    transaction != null;
                    transaction = transaction.next) {
                copies[index++] = new TransactionSnapshot(
                        transaction.id, transaction.status);
            }

            return new Snapshot(availableStock.clone(), availableFunds, copies);
        }
    }

    /* Called only while holding monitor. */
    private Transaction findTransaction(long transactionId) {
        for (Transaction transaction = transactions;
                transaction != null;
                transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    /* Called only while holding monitor, for a pending transaction. */
    private void rollback(Transaction transaction, Status status) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            availableStock[i] += quantity;
        }
        reservedFunds -= transaction.price;
        availableFunds += transaction.price;
        transaction.status = status;
    }

    private void failIfPending(Transaction transaction) {
        synchronized (monitor) {
            if (transaction.status == Status.PENDING) {
                rollback(transaction, Status.FAILED);
            }
        }
    }
}
