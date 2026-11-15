@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING,
        COMMITTED,
        FAILED,
        CANCELLED,
        UNAVAILABLE
    }

    public static final class TransactionStatus {
        public final long transactionId;
        public final Status status;

        private TransactionStatus(long transactionId, Status status) {
            this.transactionId = transactionId;
            this.status = status;
        }
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final TransactionStatus[] transactions;

        private Snapshot(int[] availableStock, long availableFunds,
                         TransactionStatus[] transactions) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactions = transactions;
        }
    }

    private static final long MAX_LONG = 9223372036854775807L;
    private static final int MAX_INT = 2147483647;

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long price;
        Status status;
        Transaction next;

        Transaction(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = Status.PENDING;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private Transaction transactions;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser are required");
        }

        this.prices = new int[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
            this.prices[i] = prices[i];
        }
        this.availableStock = new int[prices.length];
        this.reservedStock = new int[prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }

        synchronized (monitor) {
            long total = (long) availableStock[product]
                    + reservedStock[product] + quantity;
            if (total > MAX_INT) {
                throw new IllegalArgumentException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (monitor) {
            if (amount > MAX_LONG - availableFunds - reservedFunds) {
                throw new IllegalArgumentException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    /*
     * Unavailable bundles fail immediately. A successful reservation invokes
     * the dispenser without holding the state monitor.
     */
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null
                || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid transaction or bundle");
        }

        int[] bundle = new int[quantities.length];
        int[] callbackBundle = new int[quantities.length];
        boolean nonempty = false;
        long totalPrice = 0;

        for (int i = 0; i < quantities.length; i++) {
            int quantity = quantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            bundle[i] = quantity;
            callbackBundle[i] = quantity;
            if (quantity > 0) {
                nonempty = true;
            }

            long itemPrice = (long) prices[i] * quantity;
            if (itemPrice > MAX_LONG - totalPrice) {
                throw new IllegalArgumentException("Bundle price overflow");
            }
            totalPrice += itemPrice;
        }
        if (!nonempty) {
            throw new IllegalArgumentException("Bundle must not be empty");
        }

        Transaction transaction = new Transaction(transactionId, bundle, totalPrice);

        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID already used");
            }

            boolean available = availableFunds >= totalPrice;
            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            transaction.next = transactions;
            transactions = transaction;

            if (!available) {
                transaction.status = Status.UNAVAILABLE;
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= totalPrice;
            reservedFunds += totalPrice;
        }

        boolean dispensed = false;
        boolean committed = false;
        try {
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } finally {
            synchronized (monitor) {
                if (transaction.status == Status.PENDING) {
                    if (dispensed) {
                        for (int i = 0; i < bundle.length; i++) {
                            reservedStock[i] -= bundle[i];
                        }
                        reservedFunds -= totalPrice;
                        transaction.status = Status.COMMITTED;
                        committed = true;
                    } else {
                        rollback(transaction);
                        transaction.status = Status.FAILED;
                    }
                }
            }
        }
        return committed;
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }

        synchronized (monitor) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != Status.PENDING) {
                return false;
            }
            rollback(transaction);
            transaction.status = Status.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int[] stockCopy = new int[availableStock.length];
            for (int i = 0; i < availableStock.length; i++) {
                stockCopy[i] = availableStock[i];
            }

            int count = 0;
            for (Transaction current = transactions;
                 current != null; current = current.next) {
                count++;
            }

            TransactionStatus[] statusCopy = new TransactionStatus[count];
            int index = 0;
            for (Transaction current = transactions;
                 current != null; current = current.next) {
                statusCopy[index++] =
                        new TransactionStatus(current.id, current.status);
            }
            return new Snapshot(stockCopy, availableFunds, statusCopy);
        }
    }

    /* Called only while holding monitor. */
    private Transaction findTransaction(long transactionId) {
        for (Transaction current = transactions;
             current != null; current = current.next) {
            if (current.id == transactionId) {
                return current;
            }
        }
        return null;
    }

    /* Called only while holding monitor for a pending transaction. */
    private void rollback(Transaction transaction) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            availableStock[i] += quantity;
        }
        reservedFunds -= transaction.price;
        availableFunds += transaction.price;
    }
}
