@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
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
        public final Status[] transactionStatuses;

        private Snapshot(int[] availableStock, long availableFunds,
                         long[] transactionIds, Status[] transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionIds = transactionIds;
            this.transactionStatuses = transactionStatuses;
        }
    }

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long price;
        final Transaction next;
        Status status;

        Transaction(long id, int[] quantities, long price,
                    Status status, Transaction next) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.status = status;
            this.next = next;
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
            int price = prices[i];
            if (price < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
            this.prices[i] = price;
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
            int capacity = Integer.MAX_VALUE
                    - availableStock[product] - reservedStock[product];
            if (quantity > capacity) {
                throw new ArithmeticException("Stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be nonnegative");
        }

        synchronized (monitor) {
            long capacity = Long.MAX_VALUE - availableFunds - reservedFunds;
            if (amount > capacity) {
                throw new ArithmeticException("Funds overflow");
            }
            availableFunds += amount;
        }
    }

    /*
     * Valid purchase attempts consume their IDs, including rejected attempts.
     * Invalid input and price overflow are rejected before an ID is registered.
     * Purchases do not wait for resources; the dispenser itself may block.
     */
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null
                || quantities.length != prices.length) {
            throw new IllegalArgumentException("Invalid transaction or bundle");
        }

        int[] bundle = new int[quantities.length];
        int[] dispenserBundle = new int[quantities.length];
        boolean hasItems = false;
        for (int i = 0; i < quantities.length; i++) {
            int quantity = quantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            bundle[i] = quantity;
            dispenserBundle[i] = quantity;
            if (quantity > 0) {
                hasItems = true;
            }
        }
        if (!hasItems) {
            throw new IllegalArgumentException("Bundle must contain an item");
        }

        Transaction transaction;
        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("Transaction ID already used");
            }

            long total = 0;
            for (int i = 0; i < bundle.length; i++) {
                long itemCost = (long) prices[i] * bundle[i];
                if (itemCost > Long.MAX_VALUE - total) {
                    throw new ArithmeticException("Bundle price overflow");
                }
                total += itemCost;
            }

            boolean available = total <= availableFunds;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }

            transaction = new Transaction(transactionId, bundle, total,
                    available ? Status.PENDING : Status.REJECTED, transactions);
            transactions = transaction;

            if (!available) {
                return false;
            }

            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= total;
            reservedFunds += total;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, dispenserBundle);
        } catch (RuntimeException exception) {
            resolve(transaction, false);
            throw exception;
        } catch (Error error) {
            resolve(transaction, false);
            throw error;
        }

        return resolve(transaction, dispensed);
    }

    public boolean cancel(long transactionId) {
        synchronized (monitor) {
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
        synchronized (monitor) {
            int[] stock = new int[availableStock.length];
            for (int i = 0; i < stock.length; i++) {
                stock[i] = availableStock[i];
            }

            int count = 0;
            for (Transaction transaction = transactions;
                 transaction != null; transaction = transaction.next) {
                if (count == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Too many transactions for a snapshot");
                }
                count++;
            }

            long[] ids = new long[count];
            Status[] statuses = new Status[count];
            int index = 0;
            for (Transaction transaction = transactions;
                 transaction != null; transaction = transaction.next) {
                ids[index] = transaction.id;
                statuses[index] = transaction.status;
                index++;
            }
            return new Snapshot(stock, availableFunds, ids, statuses);
        }
    }

    private Transaction findTransaction(long transactionId) {
        for (Transaction transaction = transactions;
             transaction != null; transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    private boolean resolve(Transaction transaction, boolean commit) {
        synchronized (monitor) {
            if (transaction.status != Status.PENDING) {
                return false;
            }
            releaseReservation(transaction, !commit);
            transaction.status = commit ? Status.COMMITTED : Status.ROLLED_BACK;
            return commit;
        }
    }

    private void releaseReservation(Transaction transaction, boolean refund) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            if (refund) {
                availableStock[i] += quantity;
            }
        }
        reservedFunds -= transaction.price;
        if (refund) {
            availableFunds += transaction.price;
        }
    }
}
