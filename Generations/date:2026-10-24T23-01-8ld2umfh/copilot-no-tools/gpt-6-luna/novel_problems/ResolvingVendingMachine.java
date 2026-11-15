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
        CANCELLED,
        DISPENSE_FAILED
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

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
        final long id;
        final int[] quantities;
        final long price;
        TransactionStatus status;

        Transaction(long id, int[] quantities, long price, TransactionStatus status) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object stateLock = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final long[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;

    private Transaction[] transactions = new Transaction[8];
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }

        this.prices = prices.clone();
        for (int price : this.prices) {
            if (price < 0) {
                throw new IllegalArgumentException("prices must be nonnegative");
            }
        }

        this.availableStock = new int[prices.length];
        this.reservedStock = new long[prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }

        synchronized (stateLock) {
            long totalStock = (long) availableStock[product] + reservedStock[product];
            if (quantity > Integer.MAX_VALUE - totalStock) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (stateLock) {
            long totalFunds = availableFunds + reservedFunds;
            if (amount > Long.MAX_VALUE - totalFunds) {
                throw new ArithmeticException("funds overflow");
            }
            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transactionId must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("quantities must match the product count");
        }

        int[] bundle = quantities.clone();
        boolean hasQuantity = false;
        long price = 0;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (bundle[i] > 0) {
                hasQuantity = true;
                if (prices[i] > (Long.MAX_VALUE - price) / bundle[i]) {
                    throw new ArithmeticException("bundle price overflow");
                }
                price += (long) prices[i] * bundle[i];
            }
        }
        if (!hasQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        Transaction transaction;
        synchronized (stateLock) {
            if (findTransaction(transactionId) >= 0) {
                throw new IllegalArgumentException("transactionId has already been used");
            }

            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    addTransaction(new Transaction(
                            transactionId, bundle, price, TransactionStatus.UNAVAILABLE));
                    return false;
                }
            }
            if (price > availableFunds) {
                addTransaction(new Transaction(
                        transactionId, bundle, price, TransactionStatus.UNAVAILABLE));
                return false;
            }

            transaction = new Transaction(
                    transactionId, bundle, price, TransactionStatus.PENDING);
            addTransaction(transaction);

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= price;
            reservedFunds += price;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException exception) {
            synchronized (stateLock) {
                if (transaction.status == TransactionStatus.PENDING) {
                    rollBack(transaction);
                    transaction.status = TransactionStatus.DISPENSE_FAILED;
                }
            }
            throw exception;
        }

        synchronized (stateLock) {
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            if (dispensed) {
                releaseReservation(transaction);
                transaction.status = TransactionStatus.COMMITTED;
                return true;
            }

            rollBack(transaction);
            transaction.status = TransactionStatus.DISPENSE_FAILED;
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (stateLock) {
            int index = findTransaction(transactionId);
            if (index < 0) {
                return false;
            }

            Transaction transaction = transactions[index];
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            rollBack(transaction);
            transaction.status = TransactionStatus.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            int[] stockCopy = availableStock.clone();
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];

            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transactions[i].id;
                statuses[i] = transactions[i].status;
            }

            return new Snapshot(stockCopy, availableFunds, ids, statuses);
        }
    }

    private int findTransaction(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].id == transactionId) {
                return i;
            }
        }
        return -1;
    }

    private void addTransaction(Transaction transaction) {
        if (transactionCount == transactions.length) {
            if (transactions.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("transaction capacity exhausted");
            }
            int newLength = transactions.length > (Integer.MAX_VALUE - 1) / 2
                    ? Integer.MAX_VALUE
                    : transactions.length * 2 + 1;
            Transaction[] expanded = new Transaction[newLength];
            System.arraycopy(transactions, 0, expanded, 0, transactionCount);
            transactions = expanded;
        }
        transactions[transactionCount++] = transaction;
    }

    private void releaseReservation(Transaction transaction) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            reservedStock[i] -= transaction.quantities[i];
        }
        reservedFunds -= transaction.price;
    }

    private void rollBack(Transaction transaction) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            availableStock[i] += transaction.quantities[i];
        }
        availableFunds += transaction.price;
        releaseReservation(transaction);
    }
}
