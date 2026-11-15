@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private static final int MAX_STOCK = 2147483647;
    private static final long MAX_MONEY = 9223372036854775807L;

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING,
        COMMITTED,
        REJECTED,
        ROLLED_BACK,
        CANCELLED
    }

    /**
     * Detached data. Mutating these arrays cannot affect the machine.
     * Transaction IDs and statuses have matching indices, newest first.
     */
    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final Status[] statuses;

        private Snapshot(int[] availableStock, long availableFunds,
                         long[] transactionIds, Status[] statuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.statuses = statuses;
        }
    }

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long price;
        final Transaction next;
        Status status;

        Transaction(long id, int[] quantities, long price,
                    Transaction next, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.next = next;
            this.status = status;
        }
    }

    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private Transaction transactions;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser are required");
        }

        int[] copiedPrices = copy(prices);
        for (int i = 0; i < copiedPrices.length; i++) {
            if (copiedPrices[i] < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.prices = copiedPrices;
        this.dispenser = dispenser;
        this.availableStock = new int[copiedPrices.length];
        this.reservedStock = new int[copiedPrices.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("Invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be nonnegative");
        }

        synchronized (this) {
            // Reserve enough capacity for every possible rollback.
            if (quantity > MAX_STOCK
                    - availableStock[product] - reservedStock[product]) {
                throw new IllegalArgumentException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (this) {
            if (amount > MAX_MONEY - availableFunds - reservedFunds) {
                throw new IllegalArgumentException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    /**
     * Never waits for stock or funds: unavailable bundles return false and
     * consume their ID. Invalid arguments do not register a transaction.
     * IDs are retained permanently; exhausted history capacity throws
     * IllegalStateException before registration.
     */
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        requirePositiveId(transactionId);
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid bundle length");
        }

        int[] bundle = copy(quantities);
        long price = 0;
        boolean nonempty = false;

        for (int i = 0; i < bundle.length; i++) {
            int quantity = bundle[i];
            if (quantity < 0) {
                throw new IllegalArgumentException(
                        "Bundle quantities must be nonnegative");
            }
            if (quantity > 0) {
                nonempty = true;
            }

            long itemPrice = (long) prices[i] * quantity;
            if (itemPrice > MAX_MONEY - price) {
                throw new IllegalArgumentException("Bundle price overflow");
            }
            price += itemPrice;
        }

        if (!nonempty) {
            throw new IllegalArgumentException("Bundle must be nonempty");
        }

        // Allocate before reserving; the callback never receives our record.
        int[] callbackBundle = copy(bundle);
        final Transaction transaction;

        synchronized (this) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Duplicate transaction ID");
            }
            if (transactionCount == MAX_STOCK) {
                throw new IllegalStateException(
                        "Transaction history capacity exhausted");
            }

            boolean available = price <= availableFunds;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }

            transaction = new Transaction(
                    transactionId, bundle, price, transactions,
                    available ? Status.PENDING : Status.REJECTED);
            transactions = transaction;
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
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } catch (RuntimeException failure) {
            complete(transaction, false);
            throw failure;
        }

        return complete(transaction, dispensed);
    }

    public boolean cancel(long transactionId) {
        requirePositiveId(transactionId);

        synchronized (this) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != Status.PENDING) {
                return false;
            }

            releaseReservation(transaction, true);
            transaction.status = Status.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            int[] stock = copy(availableStock);
            long[] ids = new long[transactionCount];
            Status[] statuses = new Status[transactionCount];

            int index = 0;
            for (Transaction transaction = transactions;
                 transaction != null;
                 transaction = transaction.next) {
                ids[index] = transaction.id;
                statuses[index] = transaction.status;
                index++;
            }

            return new Snapshot(stock, availableFunds, ids, statuses);
        }
    }

    private boolean complete(Transaction transaction, boolean dispensed) {
        synchronized (this) {
            if (transaction.status != Status.PENDING) {
                return false;
            }

            releaseReservation(transaction, !dispensed);
            transaction.status =
                    dispensed ? Status.COMMITTED : Status.ROLLED_BACK;
            return dispensed;
        }
    }

    // Called only under this machine's monitor for a pending transaction.
    private void releaseReservation(Transaction transaction, boolean restore) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            if (restore) {
                availableStock[i] += quantity;
            }
        }

        reservedFunds -= transaction.price;
        if (restore) {
            availableFunds += transaction.price;
        }
    }

    // Called only under this machine's monitor.
    private Transaction findTransaction(long transactionId) {
        for (Transaction transaction = transactions;
             transaction != null;
             transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    private static void requirePositiveId(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
    }

    private static int[] copy(int[] source) {
        int[] result = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
