@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING, COMMITTED, FAILED, CANCELLED
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

    private static final long MAX_FUNDS = 9223372036854775807L;
    private static final int MAX_STOCK = 2147483647;

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
            this.status = Status.FAILED;
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] stock;
    private final int[] reservedStock;
    private final Dispenser dispenser;
    private long funds;
    private long reservedFunds;
    private Transaction transactions;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("prices and dispenser are required");
        }
        this.prices = new int[prices.length];
        for (int i = 0; i < prices.length; i++) {
            int price = prices[i];
            if (price < 0) {
                throw new IllegalArgumentException("negative price");
            }
            this.prices[i] = price;
        }
        this.stock = new int[prices.length];
        this.reservedStock = new int[prices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("invalid product or quantity");
        }
        synchronized (monitor) {
            if (quantity > MAX_STOCK - stock[product] - reservedStock[product]) {
                throw new IllegalArgumentException("stock overflow");
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative funds");
        }
        synchronized (monitor) {
            if (amount > MAX_FUNDS - funds - reservedFunds) {
                throw new IllegalArgumentException("funds overflow");
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("invalid transaction or bundle");
        }
        int[] reservation = new int[prices.length];
        int[] dispenserBundle = new int[prices.length];
        boolean nonempty = false;
        long price = 0;
        for (int i = 0; i < prices.length; i++) {
            int quantity = quantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            reservation[i] = quantity;
            dispenserBundle[i] = quantity;
            if (quantity > 0) {
                nonempty = true;
            }
            long itemPrice = (long) prices[i] * quantity;
            if (itemPrice > MAX_FUNDS - price) {
                throw new IllegalArgumentException("bundle price overflow");
            }
            price += itemPrice;
        }
        if (!nonempty) {
            throw new IllegalArgumentException("empty bundle");
        }

        Transaction transaction = new Transaction(transactionId, reservation, price);
        synchronized (monitor) {
            if (findTransaction(transactionId) != null) {
                throw new IllegalArgumentException("duplicate transaction ID");
            }
            transaction.next = transactions;
            transactions = transaction;

            if (funds < price) {
                return false;
            }
            for (int i = 0; i < stock.length; i++) {
                if (stock[i] < reservation[i]) {
                    return false;
                }
            }
            for (int i = 0; i < stock.length; i++) {
                stock[i] -= reservation[i];
                reservedStock[i] += reservation[i];
            }
            funds -= price;
            reservedFunds += price;
            transaction.status = Status.PENDING;
        }

        boolean dispensed;
        try {
            dispensed = dispenser.dispense(transactionId, dispenserBundle);
        } catch (RuntimeException | Error failure) {
            synchronized (monitor) {
                if (transaction.status == Status.PENDING) {
                    rollback(transaction, Status.FAILED);
                }
            }
            throw failure;
        }

        synchronized (monitor) {
            if (transaction.status != Status.PENDING) {
                return false;
            }
            if (!dispensed) {
                rollback(transaction, Status.FAILED);
                return false;
            }
            for (int i = 0; i < stock.length; i++) {
                reservedStock[i] -= transaction.quantities[i];
            }
            reservedFunds -= transaction.price;
            transaction.status = Status.COMMITTED;
            return true;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("invalid transaction ID");
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
            int count = 0;
            for (Transaction transaction = transactions; transaction != null;
                 transaction = transaction.next) {
                if (count == MAX_STOCK) {
                    throw new IllegalStateException("too many transactions to snapshot");
                }
                count++;
            }
            int[] availableStock = new int[stock.length];
            for (int i = 0; i < stock.length; i++) {
                availableStock[i] = stock[i];
            }
            TransactionStatus[] statuses = new TransactionStatus[count];
            int index = 0;
            for (Transaction transaction = transactions; transaction != null;
                 transaction = transaction.next) {
                statuses[index++] = new TransactionStatus(transaction.id, transaction.status);
            }
            return new Snapshot(availableStock, funds, statuses);
        }
    }

    // Called only while holding monitor.
    private Transaction findTransaction(long transactionId) {
        for (Transaction transaction = transactions; transaction != null;
             transaction = transaction.next) {
            if (transaction.id == transactionId) {
                return transaction;
            }
        }
        return null;
    }

    // Pending reservations are included in the overflow limits for deposits and restocks.
    private void rollback(Transaction transaction, Status status) {
        for (int i = 0; i < stock.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            stock[i] += quantity;
        }
        reservedFunds -= transaction.price;
        funds += transaction.price;
        transaction.status = status;
    }
}
