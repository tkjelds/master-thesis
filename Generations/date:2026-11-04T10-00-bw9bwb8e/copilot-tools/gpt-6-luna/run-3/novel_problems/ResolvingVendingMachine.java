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
        REJECTED
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

        private Snapshot(int[] stock, long funds, long[] ids, TransactionStatus[] statuses) {
            this.availableStock = copyInts(stock);
            this.availableFunds = funds;
            this.transactionIds = copyLongs(ids);
            this.transactionStatuses = copyStatuses(statuses);
        }

        public int[] getAvailableStock() {
            return copyInts(availableStock);
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public long[] getTransactionIds() {
            return copyLongs(transactionIds);
        }

        public TransactionStatus[] getTransactionStatuses() {
            return copyStatuses(transactionStatuses);
        }

        public TransactionStatus statusFor(long transactionId) {
            for (int i = 0; i < transactionIds.length; i++) {
                if (transactionIds[i] == transactionId) {
                    return transactionStatuses[i];
                }
            }
            return null;
        }
    }

    private static final class TransactionRecord {
        private final long id;
        private final int[] quantities;
        private final long price;
        private TransactionStatus status;

        private TransactionRecord(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = TransactionStatus.PENDING;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final long[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private TransactionRecord[] transactions = new TransactionRecord[4];
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }

        this.prices = copyInts(prices);
        for (int price : this.prices) {
            if (price < 0) {
                throw new IllegalArgumentException("prices must be nonnegative");
            }
        }
        this.availableStock = new int[this.prices.length];
        this.reservedStock = new long[this.prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }

        synchronized (monitor) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("invalid product");
            }
            long totalStock = (long) availableStock[product] + reservedStock[product];
            if ((long) quantity > Integer.MAX_VALUE - totalStock) {
                throw new IllegalArgumentException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (monitor) {
            long totalFunds = availableFunds + reservedFunds;
            if (amount > Long.MAX_VALUE - totalFunds) {
                throw new IllegalArgumentException("funds overflow");
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

        int[] bundle = copyInts(quantities);
        boolean hasPositiveQuantity = false;
        long price = 0;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (bundle[i] > 0) {
                hasPositiveQuantity = true;
            }
            long itemPrice = (long) bundle[i] * prices[i];
            if (itemPrice > Long.MAX_VALUE - price) {
                throw new IllegalArgumentException("bundle price overflow");
            }
            price += itemPrice;
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        TransactionRecord record = new TransactionRecord(transactionId, bundle, price);
        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("transaction ID has already been used");
            }
            ensureTransactionCapacity();

            boolean available = price <= availableFunds;
            for (int i = 0; i < bundle.length && available; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }

            if (!available) {
                record.status = TransactionStatus.REJECTED;
                transactions[transactionCount++] = record;
                return false;
            }

            transactions[transactionCount++] = record;
            availableFunds -= price;
            reservedFunds += price;
            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, copyInts(bundle));
        } catch (RuntimeException exception) {
            rollbackIfPending(record);
            throw exception;
        } catch (Error error) {
            rollbackIfPending(record);
            throw error;
        }

        synchronized (monitor) {
            if (record.status != TransactionStatus.PENDING) {
                return false;
            }
            if (dispensed) {
                releaseReservation(record);
                record.status = TransactionStatus.COMMITTED;
                return true;
            }
            rollback(record);
            record.status = TransactionStatus.ROLLED_BACK;
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transaction ID must be positive");
        }

        synchronized (monitor) {
            TransactionRecord record = findTransaction(transactionId);
            if (record == null || record.status != TransactionStatus.PENDING) {
                return false;
            }
            rollback(record);
            record.status = TransactionStatus.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int[] stockCopy = copyInts(availableStock);
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            for (int i = 0; i < transactionCount; i++) {
                ids[i] = transactions[i].id;
                statuses[i] = transactions[i].status;
            }
            return new Snapshot(stockCopy, availableFunds, ids, statuses);
        }
    }

    private void rollbackIfPending(TransactionRecord record) {
        synchronized (monitor) {
            if (record.status == TransactionStatus.PENDING) {
                rollback(record);
                record.status = TransactionStatus.ROLLED_BACK;
            }
        }
    }

    private void rollback(TransactionRecord record) {
        releaseReservation(record);
        availableFunds += record.price;
        for (int i = 0; i < record.quantities.length; i++) {
            availableStock[i] += record.quantities[i];
        }
    }

    private void releaseReservation(TransactionRecord record) {
        reservedFunds -= record.price;
        for (int i = 0; i < record.quantities.length; i++) {
            reservedStock[i] -= record.quantities[i];
        }
    }

    private TransactionRecord findTransaction(long transactionId) {
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
        if (transactions.length >= Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("transaction record capacity exhausted");
        }
        int newLength = transactions.length > (Integer.MAX_VALUE - 8) / 2
                ? Integer.MAX_VALUE - 8
                : transactions.length * 2;
        if (newLength <= transactionCount) {
            throw new IllegalStateException("transaction record capacity exhausted");
        }
        TransactionRecord[] expanded = new TransactionRecord[newLength];
        for (int i = 0; i < transactionCount; i++) {
            expanded[i] = transactions[i];
        }
        transactions = expanded;
    }

    private static int[] copyInts(int[] source) {
        int[] copy = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static long[] copyLongs(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static TransactionStatus[] copyStatuses(TransactionStatus[] source) {
        TransactionStatus[] copy = new TransactionStatus[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
