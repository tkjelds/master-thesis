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
        public final long[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

        private Snapshot(long[] availableStock, long availableFunds,
                         long[] transactionIds, TransactionStatus[] transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.transactionStatuses = transactionStatuses;
        }

        public long[] getAvailableStock() {
            return copy(availableStock);
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public long[] getTransactionIds() {
            return copy(transactionIds);
        }

        public TransactionStatus[] getTransactionStatuses() {
            return copy(transactionStatuses);
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

    private final Object state = new Object();
    private final int[] prices;
    private final long[] totalStock;
    private final Dispenser dispenser;

    private long totalFunds;
    private long reservedFunds;
    private final long[] reservedStock;

    private Transaction[] transactions = new Transaction[8];
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser must not be null");
        }

        this.prices = copy(prices);
        for (int price : this.prices) {
            if (price < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.dispenser = dispenser;
        this.totalStock = new long[prices.length];
        this.reservedStock = new long[prices.length];
    }

    public void restock(int product, int quantity) {
        synchronized (state) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("Invalid product");
            }
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantity must be nonnegative");
            }
            if (totalStock[product] > Long.MAX_VALUE - quantity) {
                throw new ArithmeticException("Stock overflow");
            }
            totalStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        synchronized (state) {
            if (amount < 0) {
                throw new IllegalArgumentException("Amount must be nonnegative");
            }
            if (totalFunds > Long.MAX_VALUE - amount) {
                throw new ArithmeticException("Funds overflow");
            }
            totalFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("Bundle must have one quantity per product");
        }

        int[] bundle = copy(quantities);
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

            long unitPrice = prices[i];
            if (quantity != 0 && unitPrice > Long.MAX_VALUE / quantity) {
                throw new ArithmeticException("Bundle price overflow");
            }
            long itemPrice = unitPrice * quantity;
            if (price > Long.MAX_VALUE - itemPrice) {
                throw new ArithmeticException("Bundle price overflow");
            }
            price += itemPrice;
        }

        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must contain a positive quantity");
        }

        Transaction transaction = new Transaction(
                transactionId, bundle, price, TransactionStatus.PENDING);

        synchronized (state) {
            if (findTransaction(transactionId) >= 0) {
                throw new IllegalArgumentException("Transaction ID has already been used");
            }
            ensureTransactionCapacity();

            boolean available = price <= totalFunds - reservedFunds;
            for (int i = 0; available && i < bundle.length; i++) {
                if ((long) bundle[i] > totalStock[i] - reservedStock[i]) {
                    available = false;
                }
            }

            if (!available) {
                transaction.status = TransactionStatus.UNAVAILABLE;
                transactions[transactionCount++] = transaction;
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                reservedStock[i] += bundle[i];
            }
            reservedFunds += price;
            transactions[transactionCount++] = transaction;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, copy(bundle));
        } catch (RuntimeException exception) {
            synchronized (state) {
                rollbackIfPending(transaction);
            }
            throw exception;
        }

        synchronized (state) {
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            if (!dispensed) {
                rollbackIfPending(transaction);
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                totalStock[i] -= bundle[i];
                reservedStock[i] -= bundle[i];
            }
            totalFunds -= price;
            reservedFunds -= price;
            transaction.status = TransactionStatus.COMMITTED;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }

        synchronized (state) {
            int index = findTransaction(transactionId);
            if (index < 0) {
                return false;
            }

            Transaction transaction = transactions[index];
            if (transaction.status != TransactionStatus.PENDING) {
                return false;
            }

            rollbackIfPending(transaction);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (state) {
            long[] availableStock = new long[totalStock.length];
            for (int i = 0; i < totalStock.length; i++) {
                availableStock[i] = totalStock[i] - reservedStock[i];
            }

            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transactions[i].id;
                statuses[i] = transactions[i].status;
            }

            return new Snapshot(availableStock, totalFunds - reservedFunds, ids, statuses);
        }
    }

    private void rollbackIfPending(Transaction transaction) {
        if (transaction.status != TransactionStatus.PENDING) {
            return;
        }

        for (int i = 0; i < transaction.quantities.length; i++) {
            reservedStock[i] -= transaction.quantities[i];
        }
        reservedFunds -= transaction.price;
        transaction.status = TransactionStatus.ROLLED_BACK;
    }

    private int findTransaction(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].id == transactionId) {
                return i;
            }
        }
        return -1;
    }

    private void ensureTransactionCapacity() {
        if (transactionCount < transactions.length) {
            return;
        }
        if (transactions.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("Transaction capacity exhausted");
        }

        int newLength = transactions.length <= Integer.MAX_VALUE / 2
                ? transactions.length * 2
                : Integer.MAX_VALUE;
        Transaction[] expanded = new Transaction[newLength];
        for (int i = 0; i < transactions.length; i++) {
            expanded[i] = transactions[i];
        }
        transactions = expanded;
    }

    private static int[] copy(int[] source) {
        int[] result = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static long[] copy(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static TransactionStatus[] copy(TransactionStatus[] source) {
        TransactionStatus[] result = new TransactionStatus[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
