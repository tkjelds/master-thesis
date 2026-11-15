@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        UNAVAILABLE,
        PENDING,
        COMMITTED,
        DISPENSE_FAILED,
        CANCELLED
    }

    public static final class Snapshot {
        private final int[] availableStock;
        private final long availableFunds;
        private final long[] transactionIds;
        private final TransactionStatus[] transactionStatuses;

        private Snapshot(
                int[] availableStock,
                long availableFunds,
                long[] transactionIds,
                TransactionStatus[] transactionStatuses) {
            this.availableStock = availableStock.clone();
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds.clone();
            this.transactionStatuses = transactionStatuses.clone();
        }

        public int[] getAvailableStock() {
            return availableStock.clone();
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public long[] getTransactionIds() {
            return transactionIds.clone();
        }

        public TransactionStatus[] getTransactionStatuses() {
            return transactionStatuses.clone();
        }
    }

    private static final class Transaction {
        private final long id;
        private final int[] quantities;
        private final long price;
        private TransactionStatus status;
        private Transaction next;

        private Transaction(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = TransactionStatus.PENDING;
        }
    }

    private final Object stateLock = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private Transaction firstTransaction;
    private Transaction lastTransaction;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser must not be null");
        }

        this.prices = prices.clone();
        for (int price : this.prices) {
            if (price < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.availableStock = new int[this.prices.length];
        this.reservedStock = new int[this.prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be nonnegative");
        }

        synchronized (stateLock) {
            checkProduct(product);
            long totalStock = (long) availableStock[product] + reservedStock[product];
            if (quantity > Integer.MAX_VALUE - totalStock) {
                throw new ArithmeticException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (stateLock) {
            if (amount > Long.MAX_VALUE - availableFunds - reservedFunds) {
                throw new ArithmeticException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("Bundle must contain one quantity per product");
        }

        int[] bundle = quantities.clone();
        boolean hasPositiveQuantity = false;
        for (int quantity : bundle) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must contain at least one product");
        }

        Transaction transaction;
        synchronized (stateLock) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID has already been used");
            }

            long totalPrice = 0;
            for (int i = 0; i < bundle.length; i++) {
                long itemPrice = (long) prices[i] * bundle[i];
                if (totalPrice > Long.MAX_VALUE - itemPrice) {
                    throw new ArithmeticException("Bundle price overflow");
                }
                totalPrice += itemPrice;
            }

            if (transactionCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Transaction history is full");
            }

            boolean available = availableFunds >= totalPrice;
            for (int i = 0; available && i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            transaction = new Transaction(transactionId, bundle, totalPrice);
            appendTransaction(transaction);

            if (!available) {
                transaction.status = TransactionStatus.UNAVAILABLE;
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= totalPrice;
            reservedFunds += totalPrice;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException exception) {
            finishTransaction(transaction, false);
            throw exception;
        }

        return finishTransaction(transaction, dispensed);
    }

    public boolean cancel(long transactionId) {
        synchronized (stateLock) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            rollback(transaction);
            transaction.status = TransactionStatus.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            long[] transactionIds = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            Transaction transaction = firstTransaction;
            for (int i = 0; i < transactionCount; i++) {
                transactionIds[i] = transaction.id;
                statuses[i] = transaction.status;
                transaction = transaction.next;
            }

            return new Snapshot(
                    availableStock,
                    availableFunds,
                    transactionIds,
                    statuses);
        }
    }

    private void checkProduct(int product) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("Invalid product index");
        }
    }

    private Transaction findTransaction(long transactionId) {
        for (Transaction transaction = firstTransaction;
                transaction != null;
                transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    private void appendTransaction(Transaction transaction) {
        if (lastTransaction == null) {
            firstTransaction = transaction;
        } else {
            lastTransaction.next = transaction;
        }
        lastTransaction = transaction;
        transactionCount++;
    }

    private boolean finishTransaction(Transaction transaction, boolean dispensed) {
        synchronized (stateLock) {
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            if (dispensed) {
                for (int i = 0; i < transaction.quantities.length; i++) {
                    reservedStock[i] -= transaction.quantities[i];
                }
                reservedFunds -= transaction.price;
                transaction.status = TransactionStatus.COMMITTED;
                return true;
            }

            rollback(transaction);
            transaction.status = TransactionStatus.DISPENSE_FAILED;
            return false;
        }
    }

    private void rollback(Transaction transaction) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            int quantity = transaction.quantities[i];
            availableStock[i] += quantity;
            reservedStock[i] -= quantity;
        }
        availableFunds += transaction.price;
        reservedFunds -= transaction.price;
    }
}
