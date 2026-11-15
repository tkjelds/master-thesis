@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        PENDING,
        COMMITTED,
        CANCELLED,
        FAILED,
        UNAVAILABLE
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final TransactionStatus[] transactionStatuses;

        private Snapshot(int[] stock, long funds, long[] ids, TransactionStatus[] statuses) {
            this.availableStock = stock.clone();
            this.availableFunds = funds;
            this.transactionIds = ids.clone();
            this.transactionStatuses = statuses.clone();
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

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final long[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;

    private long[] transactionIds = new long[0];
    private int[][] transactionQuantities = new int[0][];
    private long[] transactionPrices = new long[0];
    private TransactionStatus[] transactionStatuses = new TransactionStatus[0];
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

            long total = (long) availableStock[product] + reservedStock[product];
            if (total + quantity > Integer.MAX_VALUE) {
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
            if (availableFunds + reservedFunds > Long.MAX_VALUE - amount) {
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
        long price = 0;
        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("quantities must be nonnegative");
            }
            if (quantity > 0) {
                hasPositiveQuantity = true;
                long unitPrice = prices[i];
                if (unitPrice != 0 && quantity > Long.MAX_VALUE / unitPrice) {
                    throw new ArithmeticException("bundle price overflow");
                }
                long itemPrice = unitPrice * quantity;
                if (price > Long.MAX_VALUE - itemPrice) {
                    throw new ArithmeticException("bundle price overflow");
                }
                price += itemPrice;
            }
        }
        if (!hasPositiveQuantity) {
            throw new IllegalArgumentException("bundle must contain a positive quantity");
        }

        final int recordIndex;
        synchronized (monitor) {
            if (findTransaction(transactionId) >= 0) {
                throw new IllegalArgumentException("duplicate transactionId");
            }

            ensureTransactionCapacity();
            recordIndex = transactionCount;

            boolean available = availableFunds >= price;
            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            transactionIds[recordIndex] = transactionId;
            transactionQuantities[recordIndex] = bundle.clone();
            transactionPrices[recordIndex] = price;
            transactionStatuses[recordIndex] =
                    available ? TransactionStatus.PENDING : TransactionStatus.UNAVAILABLE;
            transactionCount++;

            if (!available) {
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= price;
            reservedFunds += price;
        }

        final boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, bundle.clone());
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                if (transactionStatuses[recordIndex] == TransactionStatus.PENDING) {
                    rollback(recordIndex);
                    transactionStatuses[recordIndex] = TransactionStatus.FAILED;
                }
            }
            throw exception;
        }

        synchronized (monitor) {
            if (transactionStatuses[recordIndex] != TransactionStatus.PENDING) {
                return false;
            }

            if (dispensed) {
                releaseReservation(recordIndex);
                transactionStatuses[recordIndex] = TransactionStatus.COMMITTED;
                return true;
            }

            rollback(recordIndex);
            transactionStatuses[recordIndex] = TransactionStatus.FAILED;
            return false;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("transactionId must be positive");
        }

        synchronized (monitor) {
            int index = findTransaction(transactionId);
            if (index < 0 || transactionStatuses[index] != TransactionStatus.PENDING) {
                return false;
            }

            rollback(index);
            transactionStatuses[index] = TransactionStatus.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] ids = new long[transactionCount];
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            System.arraycopy(transactionIds, 0, ids, 0, transactionCount);
            System.arraycopy(transactionStatuses, 0, statuses, 0, transactionCount);
            return new Snapshot(availableStock, availableFunds, ids, statuses);
        }
    }

    private int findTransaction(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactionIds[i] == transactionId) {
                return i;
            }
        }
        return -1;
    }

    private void ensureTransactionCapacity() {
        if (transactionCount < transactionIds.length) {
            return;
        }

        int oldCapacity = transactionIds.length;
        if (oldCapacity == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("too many transactions");
        }
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 8;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        long[] newIds = new long[newCapacity];
        int[][] newQuantities = new int[newCapacity][];
        long[] newPrices = new long[newCapacity];
        TransactionStatus[] newStatuses = new TransactionStatus[newCapacity];

        System.arraycopy(transactionIds, 0, newIds, 0, transactionCount);
        System.arraycopy(transactionQuantities, 0, newQuantities, 0, transactionCount);
        System.arraycopy(transactionPrices, 0, newPrices, 0, transactionCount);
        System.arraycopy(transactionStatuses, 0, newStatuses, 0, transactionCount);

        transactionIds = newIds;
        transactionQuantities = newQuantities;
        transactionPrices = newPrices;
        transactionStatuses = newStatuses;
    }

    private void rollback(int index) {
        int[] quantities = transactionQuantities[index];
        for (int i = 0; i < quantities.length; i++) {
            reservedStock[i] -= quantities[i];
            availableStock[i] += quantities[i];
        }
        long price = transactionPrices[index];
        reservedFunds -= price;
        availableFunds += price;
    }

    private void releaseReservation(int index) {
        int[] quantities = transactionQuantities[index];
        for (int i = 0; i < quantities.length; i++) {
            reservedStock[i] -= quantities[i];
        }
        reservedFunds -= transactionPrices[index];
    }
}
