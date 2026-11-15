@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        PENDING,
        COMMITTED,
        FAILED,
        CANCELLED,
        UNAVAILABLE
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

    private static final class TransactionRecord {
        private final long transactionId;
        private final int[] quantities;
        private final long price;
        private TransactionStatus status;

        private TransactionRecord(long transactionId, int[] quantities, long price,
                                  TransactionStatus status) {
            this.transactionId = transactionId;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final Dispenser dispenser;

    private final int[] availableStock;
    private final int[] totalStock;
    private long availableFunds;
    private long totalFunds;

    private TransactionRecord[] transactions = new TransactionRecord[0];
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

        this.dispenser = dispenser;
        this.availableStock = new int[this.prices.length];
        this.totalStock = new int[this.prices.length];
    }

    public void restock(int product, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be nonnegative");
        }

        synchronized (monitor) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("Invalid product");
            }

            if ((long) totalStock[product] + quantity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Stock overflow");
            }

            totalStock[product] += quantity;
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (monitor) {
            if (totalFunds > Long.MAX_VALUE - amount) {
                throw new IllegalArgumentException("Funds overflow");
            }

            totalFunds += amount;
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
            }

            long unitPrice = prices[i];
            if (unitPrice != 0 && quantity > Long.MAX_VALUE / unitPrice) {
                throw new IllegalArgumentException("Bundle price overflow");
            }
            long linePrice = unitPrice * quantity;
            if (price > Long.MAX_VALUE - linePrice) {
                throw new IllegalArgumentException("Bundle price overflow");
            }
            price += linePrice;
        }

        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must contain a positive quantity");
        }

        int[] callbackBundle = bundle.clone();

        synchronized (monitor) {
            if (findTransactionLocked(transactionId) >= 0) {
                throw new IllegalArgumentException("Transaction ID has already been used");
            }

            ensureTransactionCapacityLocked();

            boolean available = availableFunds >= price;
            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            if (!available) {
                addTransactionLocked(new TransactionRecord(
                        transactionId, bundle, price, TransactionStatus.UNAVAILABLE));
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
            }
            availableFunds -= price;

            addTransactionLocked(new TransactionRecord(
                    transactionId, bundle, price, TransactionStatus.PENDING));
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                int index = findTransactionLocked(transactionId);
                TransactionRecord record = transactions[index];
                if (record.status == TransactionStatus.PENDING) {
                    rollBackLocked(record);
                    record.status = TransactionStatus.FAILED;
                }
            }
            throw exception;
        }

        synchronized (monitor) {
            int index = findTransactionLocked(transactionId);
            TransactionRecord record = transactions[index];
            if (record.status == TransactionStatus.PENDING) {
                if (dispensed) {
                    for (int i = 0; i < record.quantities.length; i++) {
                        totalStock[i] -= record.quantities[i];
                    }
                    totalFunds -= record.price;
                    record.status = TransactionStatus.COMMITTED;
                } else {
                    rollBackLocked(record);
                    record.status = TransactionStatus.FAILED;
                }
            }
            return record.status == TransactionStatus.COMMITTED;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }

        synchronized (monitor) {
            int index = findTransactionLocked(transactionId);
            if (index < 0) {
                return false;
            }

            TransactionRecord record = transactions[index];
            if (record.status != TransactionStatus.PENDING) {
                return false;
            }

            rollBackLocked(record);
            record.status = TransactionStatus.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];

            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transactions[i].transactionId;
                statuses[i] = transactions[i].status;
            }

            return new Snapshot(availableStock, availableFunds, ids, statuses);
        }
    }

    private void rollBackLocked(TransactionRecord record) {
        for (int i = 0; i < record.quantities.length; i++) {
            availableStock[i] += record.quantities[i];
        }
        availableFunds += record.price;
    }

    private int findTransactionLocked(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].transactionId == transactionId) {
                return i;
            }
        }
        return -1;
    }

    private void ensureTransactionCapacityLocked() {
        if (transactionCount < transactions.length) {
            return;
        }

        int newCapacity;
        if (transactions.length == 0) {
            newCapacity = 8;
        } else if (transactions.length <= Integer.MAX_VALUE / 2) {
            newCapacity = transactions.length * 2;
        } else if (transactions.length < Integer.MAX_VALUE) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            throw new IllegalStateException("Transaction capacity exhausted");
        }

        TransactionRecord[] expanded = new TransactionRecord[newCapacity];
        for (int i = 0; i < transactionCount; i++) {
            expanded[i] = transactions[i];
        }
        transactions = expanded;
    }

    private void addTransactionLocked(TransactionRecord record) {
        transactions[transactionCount++] = record;
    }
}
