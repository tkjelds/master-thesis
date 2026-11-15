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
        UNAVAILABLE
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

        private Snapshot(int[] availableStock, long availableFunds,
                         long[] transactionIds, TransactionStatus[] transactionStatuses) {
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
        final long id;
        final int[] quantities;
        final long price;
        TransactionStatus status;

        Transaction(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities.clone();
            this.price = price;
            this.status = TransactionStatus.UNAVAILABLE;
        }
    }

    private final Object lock = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;
    private long availableFunds;
    private long reservedFunds;
    private Transaction[] transactions = new Transaction[0];
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

        this.availableStock = new int[this.prices.length];
        this.reservedStock = new int[this.prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        synchronized (lock) {
            checkProduct(product);
            if (quantity < 0) {
                throw new IllegalArgumentException("quantity must be nonnegative");
            }

            long totalStock = (long) availableStock[product] + reservedStock[product];
            if (quantity > Integer.MAX_VALUE - totalStock) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        synchronized (lock) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be nonnegative");
            }
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
        if (quantities == null) {
            throw new IllegalArgumentException("quantities must not be null");
        }

        int[] requested = quantities.clone();
        if (requested.length != prices.length) {
            throw new IllegalArgumentException("one quantity is required per product");
        }

        boolean hasPositiveQuantity = false;
        long price = 0;
        for (int i = 0; i < requested.length; i++) {
            int quantity = requested[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }

            long itemPrice = (long) quantity * prices[i];
            if (itemPrice > Long.MAX_VALUE - price) {
                throw new ArithmeticException("bundle price overflow");
            }
            price += itemPrice;
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        Transaction transaction;
        synchronized (lock) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("transactionId has already been used");
            }
            ensureTransactionCapacity();

            transaction = new Transaction(transactionId, requested, price);
            transactions[transactionCount++] = transaction;

            for (int i = 0; i < requested.length; i++) {
                if (requested[i] > availableStock[i]) {
                    return false;
                }
            }
            if (price > availableFunds) {
                return false;
            }

            for (int i = 0; i < requested.length; i++) {
                availableStock[i] -= requested[i];
                reservedStock[i] += requested[i];
            }
            availableFunds -= price;
            reservedFunds += price;
            transaction.status = TransactionStatus.PENDING;
        }

        boolean dispenseResult = false;
        RuntimeException callbackFailure = null;
        try {
            dispenseResult = dispenser.dispense(transactionId, transaction.quantities.clone());
        } catch (RuntimeException exception) {
            callbackFailure = exception;
        }

        boolean committed = false;
        synchronized (lock) {
            if (transaction.status == TransactionStatus.PENDING) {
                if (callbackFailure == null && dispenseResult) {
                    releaseReservation(transaction, false);
                    transaction.status = TransactionStatus.COMMITTED;
                    committed = true;
                } else {
                    releaseReservation(transaction, true);
                    transaction.status = TransactionStatus.ROLLED_BACK;
                }
            }
        }

        if (callbackFailure != null) {
            throw callbackFailure;
        }
        return committed;
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            releaseReservation(transaction, true);
            transaction.status = TransactionStatus.ROLLED_BACK;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transactions[i].id;
                statuses[i] = transactions[i].status;
            }
            return new Snapshot(availableStock, availableFunds, ids, statuses);
        }
    }

    private void checkProduct(int product) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product");
        }
    }

    private Transaction findTransaction(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].id == transactionId) {
                return transactions[i];
            }
        }
        return null;
    }

    private void ensureTransactionCapacity() {
        if (transactionCount < transactions.length) {
            return;
        }
        if (transactions.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("transaction capacity exhausted");
        }

        int newLength;
        if (transactions.length == 0) {
            newLength = 4;
        } else if (transactions.length <= Integer.MAX_VALUE / 2) {
            newLength = transactions.length * 2;
        } else {
            newLength = Integer.MAX_VALUE;
        }

        Transaction[] expanded = new Transaction[newLength];
        System.arraycopy(transactions, 0, expanded, 0, transactionCount);
        transactions = expanded;
    }

    private void releaseReservation(Transaction transaction, boolean refund) {
        for (int i = 0; i < transaction.quantities.length; i++) {
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
    }
}
