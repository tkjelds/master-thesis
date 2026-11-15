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
        CANCELED,
        UNAVAILABLE
    }

    public static final class Snapshot {
        public final long[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

        private Snapshot(long[] availableStock, long availableFunds,
                         long[] transactionIds,
                         TransactionStatus[] transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.transactionStatuses = transactionStatuses;
        }
    }

    private static final class TransactionRecord {
        final long id;
        final int[] quantities;
        final long price;
        TransactionStatus status;

        TransactionRecord(long id, int[] quantities, long price,
                          TransactionStatus status) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object stateMonitor = new Object();
    private final int[] prices;
    private final long[] availableStock;
    private final long[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;

    private TransactionRecord[] records = new TransactionRecord[0];
    private int recordCount;

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
        this.availableStock = new long[copiedPrices.length];
        this.reservedStock = new long[copiedPrices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }

        synchronized (stateMonitor) {
            long available = availableStock[product];
            long reserved = reservedStock[product];
            if ((long) quantity > Long.MAX_VALUE - available - reserved) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] = available + quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (stateMonitor) {
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
            throw new IllegalArgumentException("quantities must match the products");
        }

        int[] bundle = quantities.clone();
        boolean hasPositiveQuantity = false;
        long totalPrice = 0;

        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
            }

            long unitPrice = prices[i];
            long q = quantity;
            if (unitPrice != 0 && q > Long.MAX_VALUE / unitPrice) {
                throw new ArithmeticException("bundle price overflow");
            }
            long itemPrice = unitPrice * q;
            if (totalPrice > Long.MAX_VALUE - itemPrice) {
                throw new ArithmeticException("bundle price overflow");
            }
            totalPrice += itemPrice;
        }

        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        int[] callbackBundle = bundle.clone();
        TransactionRecord record;

        synchronized (stateMonitor) {
            if (findRecord(transactionId) != null) {
                throw new IllegalArgumentException("transaction ID has already been used");
            }

            ensureRecordCapacity();
            boolean available = availableFunds >= totalPrice;
            for (int i = 0; i < bundle.length && available; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            if (!available) {
                record = new TransactionRecord(
                        transactionId, bundle, totalPrice, TransactionStatus.UNAVAILABLE);
                records[recordCount++] = record;
                return false;
            }

            record = new TransactionRecord(
                    transactionId, bundle, totalPrice, TransactionStatus.PENDING);

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= totalPrice;
            reservedFunds += totalPrice;
            records[recordCount++] = record;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } catch (RuntimeException exception) {
            synchronized (stateMonitor) {
                if (record.status == TransactionStatus.PENDING) {
                    rollback(record, TransactionStatus.ROLLED_BACK);
                }
            }
            throw exception;
        }

        synchronized (stateMonitor) {
            if (record.status != TransactionStatus.PENDING) {
                return false;
            }

            if (!dispensed) {
                rollback(record, TransactionStatus.ROLLED_BACK);
                return false;
            }

            for (int i = 0; i < record.quantities.length; i++) {
                reservedStock[i] -= record.quantities[i];
            }
            reservedFunds -= record.price;
            record.status = TransactionStatus.COMMITTED;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (stateMonitor) {
            TransactionRecord record = findRecord(transactionId);
            if (record == null || record.status != TransactionStatus.PENDING) {
                return false;
            }
            rollback(record, TransactionStatus.CANCELED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            long[] stockCopy = availableStock.clone();
            long[] ids = new long[recordCount];
            TransactionStatus[] statuses = new TransactionStatus[recordCount];

            for (int i = 0; i < recordCount; i++) {
                ids[i] = records[i].id;
                statuses[i] = records[i].status;
            }

            return new Snapshot(stockCopy, availableFunds, ids, statuses);
        }
    }

    private void rollback(TransactionRecord record, TransactionStatus finalStatus) {
        for (int i = 0; i < record.quantities.length; i++) {
            int quantity = record.quantities[i];
            reservedStock[i] -= quantity;
            availableStock[i] += quantity;
        }
        reservedFunds -= record.price;
        availableFunds += record.price;
        record.status = finalStatus;
    }

    private TransactionRecord findRecord(long transactionId) {
        for (int i = 0; i < recordCount; i++) {
            if (records[i].id == transactionId) {
                return records[i];
            }
        }
        return null;
    }

    private void ensureRecordCapacity() {
        if (recordCount < records.length) {
            return;
        }

        int oldCapacity = records.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 8;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        if (newCapacity <= oldCapacity) {
            throw new OutOfMemoryError("too many transactions");
        }

        TransactionRecord[] expanded = new TransactionRecord[newCapacity];
        for (int i = 0; i < recordCount; i++) {
            expanded[i] = records[i];
        }
        records = expanded;
    }
}
