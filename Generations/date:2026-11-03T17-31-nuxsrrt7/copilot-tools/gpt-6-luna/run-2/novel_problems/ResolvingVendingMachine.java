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
    }

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long price;
        TransactionStatus status;
        boolean reserved;

        Transaction(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities.clone();
            this.price = price;
            this.status = TransactionStatus.PENDING;
        }
    }

    private final Object monitor = new Object();
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
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }

        synchronized (monitor) {
            long total = (long) availableStock[product] + reservedStock[product] + quantity;
            if (total > Integer.MAX_VALUE) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (monitor) {
            if (amount > Long.MAX_VALUE - availableFunds - reservedFunds) {
                throw new ArithmeticException("funds overflow");
            }
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
        boolean hasPositiveQuantity = false;
        long cost = 0;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (bundle[i] > 0) {
                hasPositiveQuantity = true;
            }
            long itemPrice = (long) prices[i] * bundle[i];
            if (itemPrice > Long.MAX_VALUE - cost) {
                throw new ArithmeticException("bundle price overflow");
            }
            cost += itemPrice;
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        Transaction transaction;
        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("transaction ID has already been used");
            }

            transaction = new Transaction(transactionId, bundle, cost);
            addTransaction(transaction);

            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    transaction.status = TransactionStatus.UNAVAILABLE;
                    return false;
                }
            }
            if (availableFunds < cost) {
                transaction.status = TransactionStatus.UNAVAILABLE;
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= cost;
            reservedFunds += cost;
            transaction.reserved = true;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, transaction.quantities.clone());
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                if (transaction.status == TransactionStatus.PENDING) {
                    rollBack(transaction, TransactionStatus.ROLLED_BACK);
                }
            }
            throw exception;
        }

        synchronized (monitor) {
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }
            if (dispensed) {
                reservedFunds -= transaction.price;
                for (int i = 0; i < transaction.quantities.length; i++) {
                    reservedStock[i] -= transaction.quantities[i];
                }
                transaction.reserved = false;
                transaction.status = TransactionStatus.COMMITTED;
                return true;
            }

            rollBack(transaction, TransactionStatus.ROLLED_BACK);
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transaction ID must be positive");
        }

        synchronized (monitor) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != TransactionStatus.PENDING) {
                return false;
            }
            rollBack(transaction, TransactionStatus.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transactions[i].id;
                statuses[i] = transactions[i].status;
            }
            return new Snapshot(availableStock, availableFunds, ids, statuses);
        }
    }

    private Transaction findTransaction(long id) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].id == id) {
                return transactions[i];
            }
        }
        return null;
    }

    private void addTransaction(Transaction transaction) {
        if (transactionCount == transactions.length) {
            int newLength = transactions.length == 0 ? 8 : transactions.length * 2;
            if (newLength < transactions.length) {
                throw new OutOfMemoryError("too many transactions");
            }
            Transaction[] expanded = new Transaction[newLength];
            System.arraycopy(transactions, 0, expanded, 0, transactionCount);
            transactions = expanded;
        }
        transactions[transactionCount++] = transaction;
    }

    private void rollBack(Transaction transaction, TransactionStatus status) {
        if (transaction.reserved) {
            reservedFunds -= transaction.price;
            availableFunds += transaction.price;
            for (int i = 0; i < transaction.quantities.length; i++) {
                int quantity = transaction.quantities[i];
                reservedStock[i] -= quantity;
                availableStock[i] += quantity;
            }
            transaction.reserved = false;
        }
        transaction.status = status;
    }
}
