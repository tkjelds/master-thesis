@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_COMMITTED = 2;
    public static final int STATUS_ROLLED_BACK = 3;
    public static final int STATUS_UNAVAILABLE = 4;

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final int[] transactionStatuses;

        private Snapshot(int[] availableStock, long availableFunds,
                         long[] transactionIds, int[] transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.transactionStatuses = transactionStatuses;
        }
    }

    private static final class TransactionRecord {
        private final long id;
        private final int[] quantities;
        private final long price;
        private int status;
        private TransactionRecord next;

        private TransactionRecord(long id, int[] quantities, long price, int status) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object stateMonitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private TransactionRecord transactionsHead;
    private TransactionRecord transactionsTail;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }

        this.prices = new int[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("prices must be nonnegative");
            }
            this.prices[i] = prices[i];
        }

        this.availableStock = new int[prices.length];
        this.reservedStock = new int[prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        synchronized (stateMonitor) {
            if (product < 0 || product >= prices.length) {
                throw new IllegalArgumentException("invalid product");
            }
            if (quantity < 0) {
                throw new IllegalArgumentException("quantity must be nonnegative");
            }

            long totalStock = (long) availableStock[product] + reservedStock[product];
            if ((long) quantity > Integer.MAX_VALUE - totalStock) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        synchronized (stateMonitor) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be nonnegative");
            }
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

        final TransactionRecord record;
        final int[] dispenseQuantities;

        synchronized (stateMonitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction ID");
            }
            if (quantities == null || quantities.length != prices.length) {
                throw new IllegalArgumentException("bundle must contain one quantity per product");
            }

            int[] bundle = new int[quantities.length];
            boolean hasPositiveQuantity = false;
            long totalPrice = 0;

            for (int i = 0; i < quantities.length; i++) {
                int quantity = quantities[i];
                if (quantity < 0) {
                    throw new IllegalArgumentException("quantities must be nonnegative");
                }
                bundle[i] = quantity;
                if (quantity > 0) {
                    hasPositiveQuantity = true;
                }

                long itemPrice = (long) prices[i] * quantity;
                if (itemPrice > Long.MAX_VALUE - totalPrice) {
                    throw new ArithmeticException("bundle price overflow");
                }
                totalPrice += itemPrice;
            }

            if (!hasPositiveQuantity) {
                throw new IllegalArgumentException("bundle must contain at least one item");
            }

            boolean available = totalPrice <= availableFunds;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }

            dispenseQuantities = available ? copyArray(bundle) : null;
            record = new TransactionRecord(
                transactionId,
                bundle,
                totalPrice,
                available ? STATUS_PENDING : STATUS_UNAVAILABLE
            );
            appendTransaction(record);

            if (!available) {
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= totalPrice;
            reservedFunds += totalPrice;
        }

        final boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, dispenseQuantities);
        } catch (RuntimeException exception) {
            synchronized (stateMonitor) {
                rollbackIfPending(record);
            }
            throw exception;
        }

        synchronized (stateMonitor) {
            if (record.status != STATUS_PENDING) {
                return false;
            }

            if (dispensed) {
                for (int i = 0; i < record.quantities.length; i++) {
                    reservedStock[i] -= record.quantities[i];
                }
                reservedFunds -= record.price;
                record.status = STATUS_COMMITTED;
                return true;
            }

            rollbackIfPending(record);
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (stateMonitor) {
            TransactionRecord record = findTransaction(transactionId);
            if (record == null || record.status != STATUS_PENDING) {
                return false;
            }
            rollbackIfPending(record);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (stateMonitor) {
            int[] stockCopy = copyArray(availableStock);
            long[] ids = new long[transactionCount];
            int[] statuses = new int[transactionCount];

            TransactionRecord record = transactionsHead;
            int index = 0;
            while (record != null) {
                ids[index] = record.id;
                statuses[index] = record.status;
                index++;
                record = record.next;
            }

            return new Snapshot(stockCopy, availableFunds, ids, statuses);
        }
    }

    private TransactionRecord findTransaction(long transactionId) {
        TransactionRecord record = transactionsHead;
        while (record != null) {
            if (record.id == transactionId) {
                return record;
            }
            record = record.next;
        }
        return null;
    }

    private void appendTransaction(TransactionRecord record) {
        if (transactionsTail == null) {
            transactionsHead = record;
        } else {
            transactionsTail.next = record;
        }
        transactionsTail = record;
        transactionCount++;
    }

    private void rollbackIfPending(TransactionRecord record) {
        if (record.status != STATUS_PENDING) {
            return;
        }

        for (int i = 0; i < record.quantities.length; i++) {
            reservedStock[i] -= record.quantities[i];
            availableStock[i] += record.quantities[i];
        }
        reservedFunds -= record.price;
        availableFunds += record.price;
        record.status = STATUS_ROLLED_BACK;
    }

    private static int[] copyArray(int[] source) {
        int[] copy = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
