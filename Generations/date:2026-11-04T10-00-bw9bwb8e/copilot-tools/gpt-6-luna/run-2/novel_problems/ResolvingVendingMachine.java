@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        PENDING,
        COMMITTED,
        ROLLED_BACK,
        CANCELLED,
        UNAVAILABLE,
        REJECTED
    }

    public static final class Snapshot {
        public final long[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

        private Snapshot(long[] availableStock, long availableFunds,
                         long[] transactionIds, TransactionStatus[] transactionStatuses) {
            this.availableStock = availableStock.clone();
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds.clone();
            this.transactionStatuses = transactionStatuses.clone();
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
    }

    private static final class Transaction {
        private final long id;
        private int[] bundle;
        private long price;
        private TransactionStatus status;
        private Transaction next;

        private Transaction(long id) {
            this.id = id;
            this.status = TransactionStatus.REJECTED;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final long[] availableStock;
    private final long[] reservedStock;
    private final Dispenser dispenser;
    private long availableFunds;
    private long reservedFunds;
    private Transaction transactions;

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

        this.availableStock = new long[this.prices.length];
        this.reservedStock = new long[this.prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        synchronized (monitor) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("invalid product");
            }
            if (quantity < 0) {
                throw new IllegalArgumentException("quantity must be nonnegative");
            }
            long totalStock = availableStock[product] + reservedStock[product];
            if ((long) quantity > Long.MAX_VALUE - totalStock) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        synchronized (monitor) {
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
            throw new IllegalArgumentException("transaction ID must be positive");
        }
        int[] requested = quantities == null ? null : quantities.clone();
        Transaction transaction;

        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction ID");
            }

            transaction = new Transaction(transactionId);
            transaction.next = transactions;
            transactions = transaction;

            if (requested == null || requested.length != prices.length) {
                throw new IllegalArgumentException("bundle must contain one quantity per product");
            }

            boolean hasProduct = false;
            long totalPrice = 0;
            for (int i = 0; i < requested.length; i++) {
                int quantity = requested[i];
                if (quantity < 0) {
                    throw new IllegalArgumentException("quantities must be nonnegative");
                }
                if (quantity > 0) {
                    hasProduct = true;
                }

                int unitPrice = prices[i];
                if (unitPrice != 0
                        && (long) quantity > (Long.MAX_VALUE - totalPrice) / unitPrice) {
                    throw new ArithmeticException("bundle price overflow");
                }
                totalPrice += (long) quantity * unitPrice;
            }

            if (!hasProduct) {
                throw new IllegalArgumentException("bundle must contain a positive quantity");
            }

            transaction.bundle = requested;
            transaction.price = totalPrice;

            for (int i = 0; i < requested.length; i++) {
                if (availableStock[i] < requested[i]) {
                    transaction.status = TransactionStatus.UNAVAILABLE;
                    return false;
                }
            }
            if (availableFunds < totalPrice) {
                transaction.status = TransactionStatus.UNAVAILABLE;
                return false;
            }

            for (int i = 0; i < requested.length; i++) {
                availableStock[i] -= requested[i];
                reservedStock[i] += requested[i];
            }
            availableFunds -= totalPrice;
            reservedFunds += totalPrice;
            transaction.status = TransactionStatus.PENDING;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, requested.clone());
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                if (transaction.status == TransactionStatus.PENDING) {
                    rollback(transaction, TransactionStatus.ROLLED_BACK);
                }
            }
            throw exception;
        }

        synchronized (monitor) {
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            if (dispensed) {
                for (int i = 0; i < transaction.bundle.length; i++) {
                    reservedStock[i] -= transaction.bundle[i];
                }
                reservedFunds -= transaction.price;
                transaction.status = TransactionStatus.COMMITTED;
                return true;
            }

            rollback(transaction, TransactionStatus.ROLLED_BACK);
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (monitor) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != TransactionStatus.PENDING) {
                return false;
            }
            rollback(transaction, TransactionStatus.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int count = 0;
            for (Transaction transaction = transactions;
                 transaction != null; transaction = transaction.next) {
                count++;
            }

            long[] ids = new long[count];
            TransactionStatus[] statuses = new TransactionStatus[count];
            int index = 0;
            for (Transaction transaction = transactions;
                 transaction != null; transaction = transaction.next) {
                ids[index] = transaction.id;
                statuses[index] = transaction.status;
                index++;
            }

            return new Snapshot(availableStock, availableFunds, ids, statuses);
        }
    }

    private Transaction findTransaction(long transactionId) {
        for (Transaction transaction = transactions;
             transaction != null; transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    private void rollback(Transaction transaction, TransactionStatus status) {
        for (int i = 0; i < transaction.bundle.length; i++) {
            reservedStock[i] -= transaction.bundle[i];
            availableStock[i] += transaction.bundle[i];
        }
        reservedFunds -= transaction.price;
        availableFunds += transaction.price;
        transaction.status = status;
    }
}
