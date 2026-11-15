@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionState {
        PENDING,
        COMMITTED,
        CANCELLED,
        FAILED,
        UNAVAILABLE
    }

    public static final class TransactionSnapshot {
        public final long transactionId;
        public final TransactionState state;

        private TransactionSnapshot(long transactionId, TransactionState state) {
            this.transactionId = transactionId;
            this.state = state;
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

    private static final class TransactionRecord {
        final long transactionId;
        final int[] quantities;
        final long price;
        TransactionState state;
        TransactionRecord next;

        TransactionRecord(long transactionId, int[] quantities, long price) {
            this.transactionId = transactionId;
            this.quantities = quantities;
            this.price = price;
            this.state = TransactionState.PENDING;
        }
    }

    private final Object lock = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private TransactionRecord firstTransaction;
    private TransactionRecord lastTransaction;
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
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("Invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be nonnegative");
        }

        synchronized (lock) {
            long total = (long) availableStock[product] + reservedStock[product] + quantity;
            if (total > Integer.MAX_VALUE) {
                throw new ArithmeticException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (lock) {
            long total = availableFunds + reservedFunds;
            if (amount > Long.MAX_VALUE - total) {
                throw new ArithmeticException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("Quantities must not be null");
        }

        int[] bundle = quantities.clone();
        if (bundle.length != prices.length) {
            throw new IllegalArgumentException("One quantity is required per product");
        }

        boolean hasPositiveQuantity = false;
        long price = 0;
        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
                long unitPrice = prices[i];
                if (unitPrice > Long.MAX_VALUE / quantity) {
                    throw new ArithmeticException("Bundle price overflow");
                }
                long productPrice = unitPrice * quantity;
                if (productPrice > Long.MAX_VALUE - price) {
                    throw new ArithmeticException("Bundle price overflow");
                }
                price += productPrice;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("A bundle must contain a positive quantity");
        }

        TransactionRecord record = new TransactionRecord(transactionId, bundle, price);
        synchronized (lock) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID has already been used");
            }
            if (transactionCount == Integer.MAX_VALUE) {
                throw new ArithmeticException("Too many transactions");
            }

            appendTransaction(record);
            if (!canReserve(bundle, price)) {
                record.state = TransactionState.UNAVAILABLE;
                return false;
            }

            reserve(record);
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException exception) {
            synchronized (lock) {
                if (record.state == TransactionState.PENDING) {
                    rollback(record, TransactionState.FAILED);
                }
            }
            throw exception;
        }

        synchronized (lock) {
            if (record.state != TransactionState.PENDING) {
                return false;
            }
            if (dispensed) {
                commit(record);
                return true;
            }
            rollback(record, TransactionState.FAILED);
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            TransactionRecord record = findTransaction(transactionId);
            if (record == null || record.state != TransactionState.PENDING) {
                return false;
            }
            rollback(record, TransactionState.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            int[] stockCopy = availableStock.clone();
            TransactionSnapshot[] transactionCopies = new TransactionSnapshot[transactionCount];
            TransactionRecord record = firstTransaction;
            int index = 0;
            while (record != null) {
                transactionCopies[index++] =
                        new TransactionSnapshot(record.transactionId, record.state);
                record = record.next;
            }
            return new Snapshot(stockCopy, availableFunds, transactionCopies);
        }
    }

    private boolean canReserve(int[] quantities, long price) {
        if (availableFunds < price) {
            return false;
        }
        for (int i = 0; i < quantities.length; i++) {
            if (availableStock[i] < quantities[i]) {
                return false;
            }
        }
        return true;
    }

    private void reserve(TransactionRecord record) {
        for (int i = 0; i < record.quantities.length; i++) {
            int quantity = record.quantities[i];
            availableStock[i] -= quantity;
            reservedStock[i] += quantity;
        }
        availableFunds -= record.price;
        reservedFunds += record.price;
    }

    private void commit(TransactionRecord record) {
        for (int i = 0; i < record.quantities.length; i++) {
            reservedStock[i] -= record.quantities[i];
        }
        reservedFunds -= record.price;
        record.state = TransactionState.COMMITTED;
    }

    private void rollback(TransactionRecord record, TransactionState finalState) {
        for (int i = 0; i < record.quantities.length; i++) {
            int quantity = record.quantities[i];
            reservedStock[i] -= quantity;
            availableStock[i] += quantity;
        }
        reservedFunds -= record.price;
        availableFunds += record.price;
        record.state = finalState;
    }

    private TransactionRecord findTransaction(long transactionId) {
        TransactionRecord record = firstTransaction;
        while (record != null) {
            if (record.transactionId == transactionId) {
                return record;
            }
            record = record.next;
        }
        return null;
    }

    private void appendTransaction(TransactionRecord record) {
        if (lastTransaction == null) {
            firstTransaction = record;
        } else {
            lastTransaction.next = record;
        }
        lastTransaction = record;
        transactionCount++;
    }
}
