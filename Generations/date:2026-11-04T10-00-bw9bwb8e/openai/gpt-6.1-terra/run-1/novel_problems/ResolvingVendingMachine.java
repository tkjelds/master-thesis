@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING,
        COMMITTED,
        CANCELLED,
        ROLLED_BACK,
        REJECTED
    }

    public static final class TransactionSnapshot {
        public final long transactionId;
        public final Status status;

        private TransactionSnapshot(long transactionId, Status status) {
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

    private static final long MAX_LONG = 9223372036854775807L;
    private static final int MAX_INT = 2147483647;

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long price;
        Status status;
        Transaction next;

        Transaction(long id, int[] quantities, long price, Status status) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final Dispenser dispenser;
    private final long[] availableStock;
    private final long[] reservedStock;

    private long availableFunds;
    private long reservedFunds;
    private Transaction transactions;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser are required");
        }

        int[] priceCopy = prices.clone();
        for (int price : priceCopy) {
            if (price < 0) {
                throw new IllegalArgumentException("Negative price");
            }
        }

        this.prices = priceCopy;
        this.dispenser = dispenser;
        this.availableStock = new long[priceCopy.length];
        this.reservedStock = new long[priceCopy.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("Invalid product or quantity");
        }

        synchronized (monitor) {
            if ((long) quantity > MAX_LONG - reservedStock[product]
                    - availableStock[product]) {
                throw new ArithmeticException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount");
        }

        synchronized (monitor) {
            if (amount > MAX_LONG - reservedFunds - availableFunds) {
                throw new ArithmeticException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null
                || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid transaction or bundle");
        }

        int[] reservation = quantities.clone();
        boolean nonempty = false;
        long total = 0;

        for (int i = 0; i < reservation.length; i++) {
            int quantity = reservation[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative quantity");
            }
            if (quantity > 0) {
                nonempty = true;
            }

            // Both operands are nonnegative ints, so their product fits in long.
            long productPrice = (long) prices[i] * quantity;
            if (productPrice > MAX_LONG - total) {
                throw new ArithmeticException("Bundle price overflow");
            }
            total += productPrice;
        }

        if (!nonempty) {
            throw new IllegalArgumentException("Empty bundle");
        }

        // The callback never receives the array held in the reservation.
        int[] callbackQuantities = reservation.clone();
        Transaction transaction;

        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID already used");
            }

            // Snapshots use arrays, so limit the number of recorded IDs.
            if (transactionCount == MAX_INT) {
                throw new IllegalStateException("Transaction record limit reached");
            }

            boolean available = availableFunds >= total;
            for (int i = 0; i < reservation.length; i++) {
                if (availableStock[i] < reservation[i]) {
                    available = false;
                }
            }

            transaction = new Transaction(
                    transactionId, reservation, total,
                    available ? Status.PENDING : Status.REJECTED);
            transaction.next = transactions;
            transactions = transaction;
            transactionCount++;

            if (!available) {
                return false;
            }

            for (int i = 0; i < reservation.length; i++) {
                availableStock[i] -= reservation[i];
                reservedStock[i] += reservation[i];
            }
            availableFunds -= total;
            reservedFunds += total;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, callbackQuantities);
        } catch (RuntimeException exception) {
            rollbackIfPending(transaction);
            throw exception;
        } catch (Error error) {
            rollbackIfPending(transaction);
            throw error;
        }

        synchronized (monitor) {
            if (transaction.status != Status.PENDING) {
                return false;
            }

            if (!dispensed) {
                rollback(transaction, Status.ROLLED_BACK);
                return false;
            }

            for (int i = 0; i < transaction.quantities.length; i++) {
                reservedStock[i] -= transaction.quantities[i];
            }
            reservedFunds -= transaction.price;
            transaction.status = Status.COMMITTED;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }

        synchronized (monitor) {
            Transaction transaction = findTransaction(transactionId);
            if (transaction == null || transaction.status != Status.PENDING) {
                return false;
            }
            rollback(transaction, Status.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] stockCopy = availableStock.clone();
            TransactionSnapshot[] transactionCopies =
                    new TransactionSnapshot[transactionCount];

            Transaction current = transactions;
            int index = 0;
            while (current != null) {
                transactionCopies[index++] =
                        new TransactionSnapshot(current.id, current.status);
                current = current.next;
            }

            // Transaction entries are ordered from newest to oldest.
            return new Snapshot(stockCopy, availableFunds, transactionCopies);
        }
    }

    // Called only while holding monitor.
    private Transaction findTransaction(long transactionId) {
        Transaction current = transactions;
        while (current != null) {
            if (current.id == transactionId) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void rollbackIfPending(Transaction transaction) {
        synchronized (monitor) {
            if (transaction.status == Status.PENDING) {
                rollback(transaction, Status.ROLLED_BACK);
            }
        }
    }

    // Called only while holding monitor and for a pending transaction.
    private void rollback(Transaction transaction, Status finalStatus) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            reservedStock[i] -= transaction.quantities[i];
            availableStock[i] += transaction.quantities[i];
        }
        reservedFunds -= transaction.price;
        availableFunds += transaction.price;
        transaction.status = finalStatus;
    }
}
