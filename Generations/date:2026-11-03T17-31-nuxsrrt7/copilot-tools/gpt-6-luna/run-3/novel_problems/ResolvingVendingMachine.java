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

    public static final class TransactionView {
        public final long transactionId;
        public final TransactionStatus status;

        private TransactionView(long transactionId, TransactionStatus status) {
            this.transactionId = transactionId;
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final TransactionView[] transactions;

        private Snapshot(int[] availableStock, long availableFunds,
                         TransactionView[] transactions) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactions = transactions;
        }

        public int[] getAvailableStock() {
            return availableStock.clone();
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public TransactionView[] getTransactions() {
            return transactions.clone();
        }
    }

    private static final class TransactionRecord {
        private final long transactionId;
        private final int[] quantities;
        private final long price;
        private TransactionStatus status;

        private TransactionRecord(long transactionId, int[] quantities,
                                  long price, TransactionStatus status) {
            this.transactionId = transactionId;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private TransactionRecord[] transactions = new TransactionRecord[8];
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }

        int[] copiedPrices = prices.clone();
        for (int price : copiedPrices) {
            if (price < 0) {
                throw new IllegalArgumentException("prices must be nonnegative");
            }
        }

        this.prices = copiedPrices;
        this.availableStock = new int[copiedPrices.length];
        this.reservedStock = new int[copiedPrices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        synchronized (monitor) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("invalid product");
            }
            long total = (long) availableStock[product]
                    + reservedStock[product] + quantity;
            if (total > Integer.MAX_VALUE) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        synchronized (monitor) {
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

        int[] bundle = quantities.clone();
        if (bundle.length != prices.length) {
            throw new IllegalArgumentException("one quantity is required per product");
        }

        boolean hasPositiveQuantity = false;
        long cost = 0;
        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }

            long price = prices[i];
            if (price != 0 && quantity > Long.MAX_VALUE / price) {
                throw new ArithmeticException("bundle price overflow");
            }
            long itemCost = price * quantity;
            if (itemCost > Long.MAX_VALUE - cost) {
                throw new ArithmeticException("bundle price overflow");
            }
            cost += itemCost;
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        int[] callbackBundle = bundle.clone();

        synchronized (monitor) {
            if (findTransaction(transactionId) >= 0) {
                throw new IllegalArgumentException("transactionId has already been used");
            }

            boolean available = cost <= availableFunds;
            for (int i = 0; available && i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }

            ensureTransactionCapacity();
            TransactionRecord record = new TransactionRecord(
                    transactionId,
                    bundle,
                    cost,
                    available ? TransactionStatus.PENDING : TransactionStatus.UNAVAILABLE);
            transactions[transactionCount++] = record;

            if (!available) {
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= cost;
            reservedFunds += cost;
        }

        final boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                if (recordIsPending(transactionId)) {
                    rollback(findTransaction(transactionId), TransactionStatus.ROLLED_BACK);
                }
            }
            throw exception;
        }

        synchronized (monitor) {
            int index = findTransaction(transactionId);
            TransactionRecord record = transactions[index];
            if (record.status != TransactionStatus.PENDING) {
                return false;
            }

            if (dispensed) {
                for (int i = 0; i < record.quantities.length; i++) {
                    reservedStock[i] -= record.quantities[i];
                }
                reservedFunds -= record.price;
                record.status = TransactionStatus.COMMITTED;
                return true;
            }

            rollback(index, TransactionStatus.ROLLED_BACK);
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transactionId must be positive");
        }

        synchronized (monitor) {
            int index = findTransaction(transactionId);
            if (index < 0 || transactions[index].status != TransactionStatus.PENDING) {
                return false;
            }
            rollback(index, TransactionStatus.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int[] stockCopy = availableStock.clone();
            TransactionView[] transactionCopies = new TransactionView[transactionCount];
            for (int i = 0; i < transactionCount; i++) {
                TransactionRecord record = transactions[i];
                transactionCopies[i] = new TransactionView(
                        record.transactionId, record.status);
            }
            return new Snapshot(stockCopy, availableFunds, transactionCopies);
        }
    }

    private int findTransaction(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].transactionId == transactionId) {
                return i;
            }
        }
        return -1;
    }

    private void ensureTransactionCapacity() {
        if (transactionCount < transactions.length) {
            return;
        }
        int oldCapacity = transactions.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity <= oldCapacity) {
            throw new IllegalStateException("too many transactions");
        }
        TransactionRecord[] expanded = new TransactionRecord[newCapacity];
        System.arraycopy(transactions, 0, expanded, 0, transactionCount);
        transactions = expanded;
    }

    private boolean recordIsPending(long transactionId) {
        int index = findTransaction(transactionId);
        return index >= 0 && transactions[index].status == TransactionStatus.PENDING;
    }

    private void rollback(int index, TransactionStatus finalStatus) {
        TransactionRecord record = transactions[index];
        for (int i = 0; i < record.quantities.length; i++) {
            int quantity = record.quantities[i];
            availableStock[i] += quantity;
            reservedStock[i] -= quantity;
        }
        availableFunds += record.price;
        reservedFunds -= record.price;
        record.status = finalStatus;
    }
}
