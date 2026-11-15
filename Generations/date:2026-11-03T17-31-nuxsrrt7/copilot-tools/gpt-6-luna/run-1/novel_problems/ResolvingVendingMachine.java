@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        PENDING,
        COMMITTED,
        ROLLED_BACK,
        CANCELLED,
        UNAVAILABLE
    }

    public static final class Snapshot {
        private final long[] availableStock;
        private final long availableFunds;
        private final long[] transactionIds;
        private final TransactionStatus[] transactionStatuses;

        private Snapshot(long[] availableStock, long availableFunds,
                         long[] transactionIds, TransactionStatus[] transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.transactionStatuses = transactionStatuses;
        }

        public long[] getAvailableStock() {
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

        public TransactionStatus getTransactionStatus(long transactionId) {
            for (int i = 0; i < transactionIds.length; i++) {
                if (transactionIds[i] == transactionId) {
                    return transactionStatuses[i];
                }
            }
            return null;
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
    private final long[] availableStock;
    private final long[] totalStock;
    private final Dispenser dispenser;
    private long availableFunds;
    private long totalFunds;
    private Transaction firstTransaction;
    private Transaction lastTransaction;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("prices and dispenser must not be null");
        }
        this.prices = prices.clone();
        for (int price : this.prices) {
            if (price < 0) {
                throw new IllegalArgumentException("prices must be nonnegative");
            }
        }
        this.availableStock = new long[this.prices.length];
        this.totalStock = new long[this.prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("invalid product or quantity");
        }
        synchronized (stateLock) {
            if (totalStock[product] > Long.MAX_VALUE - quantity) {
                throw new ArithmeticException("stock overflow");
            }
            totalStock[product] += quantity;
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (stateLock) {
            if (totalFunds > Long.MAX_VALUE - amount) {
                throw new ArithmeticException("funds overflow");
            }
            totalFunds += amount;
            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transaction ID must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("bundle must contain one quantity per product");
        }

        int[] bundle = quantities.clone();
        boolean nonempty = false;
        long price = 0;
        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (quantity > 0) {
                nonempty = true;
            }
            long productPrice = (long) prices[i] * quantity;
            if (price > Long.MAX_VALUE - productPrice) {
                throw new ArithmeticException("bundle price overflow");
            }
            price += productPrice;
        }
        if (!nonempty) {
            throw new IllegalArgumentException("bundle must contain at least one item");
        }

        Transaction transaction;
        synchronized (stateLock) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("transaction ID has already been used");
            }

            transaction = new Transaction(transactionId, bundle, price);
            appendTransaction(transaction);
            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    transaction.status = TransactionStatus.UNAVAILABLE;
                    return false;
                }
            }
            if (availableFunds < price) {
                transaction.status = TransactionStatus.UNAVAILABLE;
                return false;
            }
            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
            }
            availableFunds -= price;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException exception) {
            synchronized (stateLock) {
                rollbackIfPending(transaction);
            }
            throw exception;
        }

        synchronized (stateLock) {
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }
            if (!dispensed) {
                rollback(transaction, TransactionStatus.ROLLED_BACK);
                return false;
            }
            for (int i = 0; i < transaction.quantities.length; i++) {
                totalStock[i] -= transaction.quantities[i];
            }
            totalFunds -= transaction.price;
            transaction.status = TransactionStatus.COMMITTED;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (stateLock) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != TransactionStatus.PENDING) {
                return false;
            }
            rollback(transaction, TransactionStatus.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            long[] stockCopy = availableStock.clone();
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            Transaction transaction = firstTransaction;
            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transaction.id;
                statuses[i] = transaction.status;
                transaction = transaction.next;
            }
            return new Snapshot(stockCopy, availableFunds, ids, statuses);
        }
    }

    private Transaction findTransaction(long transactionId) {
        Transaction transaction = firstTransaction;
        while (transaction != null) {
            if (transaction.id == transactionId) {
                return transaction;
            }
            transaction = transaction.next;
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

    private void rollbackIfPending(Transaction transaction) {
        if (transaction.status == TransactionStatus.PENDING) {
            rollback(transaction, TransactionStatus.ROLLED_BACK);
        }
    }

    private void rollback(Transaction transaction, TransactionStatus status) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            availableStock[i] += transaction.quantities[i];
        }
        availableFunds += transaction.price;
        transaction.status = status;
    }
}
