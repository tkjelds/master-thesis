@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        PENDING,
        COMMITTED,
        CANCELLED,
        ROLLED_BACK,
        UNAVAILABLE
    }

    public static final class TransactionSnapshot {
        public final long transactionId;
        public final TransactionStatus status;

        private TransactionSnapshot(long transactionId, TransactionStatus status) {
            this.transactionId = transactionId;
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final long[] availableStock;
        public final long availableFunds;
        public final TransactionSnapshot[] transactions;

        private Snapshot(long[] availableStock, long availableFunds,
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
        TransactionStatus status;
        TransactionRecord next;

        TransactionRecord(long transactionId, int[] quantities, long price) {
            this.transactionId = transactionId;
            this.quantities = quantities;
            this.price = price;
            this.status = TransactionStatus.PENDING;
        }
    }

    private final Object stateLock = new Object();
    private final int[] prices;
    private final long[] availableStock;
    private final long[] reservedStock;
    private final Dispenser dispenser;
    private long availableFunds;
    private long reservedFunds;
    private TransactionRecord transactions;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser must not be null");
        }

        int[] copiedPrices = prices.clone();
        for (int price : copiedPrices) {
            if (price < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.prices = copiedPrices;
        this.availableStock = new long[copiedPrices.length];
        this.reservedStock = new long[copiedPrices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        synchronized (stateLock) {
            if (product < 0 || product >= prices.length || quantity < 0) {
                throw new IllegalArgumentException("Invalid product or quantity");
            }

            long available = availableStock[product];
            long reserved = reservedStock[product];
            if (available > Long.MAX_VALUE - reserved
                    || available + reserved > Long.MAX_VALUE - quantity) {
                throw new IllegalArgumentException("Stock overflow");
            }

            availableStock[product] = available + quantity;
        }
    }

    public void insertFunds(long amount) {
        synchronized (stateLock) {
            if (amount < 0) {
                throw new IllegalArgumentException("Amount must be nonnegative");
            }
            if (availableFunds > Long.MAX_VALUE - reservedFunds
                    || availableFunds + reservedFunds > Long.MAX_VALUE - amount) {
                throw new IllegalArgumentException("Funds overflow");
            }

            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid bundle");
        }

        int[] bundle = quantities.clone();
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

            long productCost = (long) prices[i] * quantity;
            if (price > Long.MAX_VALUE - productCost) {
                throw new IllegalArgumentException("Bundle price overflow");
            }
            price += productCost;
        }

        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("Bundle must contain a positive quantity");
        }

        TransactionRecord record;
        synchronized (stateLock) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Duplicate transaction ID");
            }

            record = new TransactionRecord(transactionId, bundle, price);
            record.next = transactions;
            transactions = record;

            boolean available = availableFunds >= price;
            for (int i = 0; available && i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            if (!available) {
                record.status = TransactionStatus.UNAVAILABLE;
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= price;
            reservedFunds += price;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException exception) {
            synchronized (stateLock) {
                if (record.status == TransactionStatus.PENDING) {
                    rollback(record, TransactionStatus.ROLLED_BACK);
                }
            }
            throw exception;
        }

        synchronized (stateLock) {
            if (record.status != TransactionStatus.PENDING) {
                return false;
            }

            if (dispensed) {
                for (int i = 0; i < bundle.length; i++) {
                    reservedStock[i] -= bundle[i];
                }
                reservedFunds -= price;
                record.status = TransactionStatus.COMMITTED;
                return true;
            }

            rollback(record, TransactionStatus.ROLLED_BACK);
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (stateLock) {
            TransactionRecord record = findTransaction(transactionId);
            if (record == null || record.status != TransactionStatus.PENDING) {
                return false;
            }

            rollback(record, TransactionStatus.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            long[] stockCopy = availableStock.clone();

            int count = 0;
            for (TransactionRecord record = transactions; record != null; record = record.next) {
                count++;
            }

            TransactionSnapshot[] transactionCopies = new TransactionSnapshot[count];
            int index = 0;
            for (TransactionRecord record = transactions; record != null; record = record.next) {
                transactionCopies[index++] =
                        new TransactionSnapshot(record.transactionId, record.status);
            }

            return new Snapshot(stockCopy, availableFunds, transactionCopies);
        }
    }

    private TransactionRecord findTransaction(long transactionId) {
        for (TransactionRecord record = transactions; record != null; record = record.next) {
            if (record.transactionId == transactionId) {
                return record;
            }
        }
        return null;
    }

    private void rollback(TransactionRecord record, TransactionStatus finalStatus) {
        for (int i = 0; i < record.quantities.length; i++) {
            reservedStock[i] -= record.quantities[i];
            availableStock[i] += record.quantities[i];
        }
        reservedFunds -= record.price;
        availableFunds += record.price;
        record.status = finalStatus;
    }
}
