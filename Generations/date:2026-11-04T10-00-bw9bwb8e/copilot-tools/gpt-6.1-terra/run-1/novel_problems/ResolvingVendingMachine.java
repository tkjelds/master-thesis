@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum TransactionStatus {
        PENDING, COMMITTED, CANCELLED, FAILED, UNAVAILABLE
    }

    // Immutable linked list node; snapshots list transactions in registration order.
    public static final class TransactionSnapshot {
        private final long transactionId;
        private final TransactionStatus status;
        private final TransactionSnapshot next;

        private TransactionSnapshot(long transactionId, TransactionStatus status,
                                    TransactionSnapshot next) {
            this.transactionId = transactionId;
            this.status = status;
            this.next = next;
        }

        public long getTransactionId() {
            return transactionId;
        }

        public TransactionStatus getStatus() {
            return status;
        }

        public TransactionSnapshot getNext() {
            return next;
        }
    }

    public static final class Snapshot {
        private final int[] availableStock;
        private final long availableFunds;
        private final TransactionSnapshot transactions;

        private Snapshot(int[] availableStock, long availableFunds,
                         TransactionSnapshot transactions) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactions = transactions;
        }

        public int[] getAvailableStock() {
            return availableStock.clone();
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public TransactionSnapshot getTransactions() {
            return transactions;
        }
    }

    private static final class Transaction {
        final long id;
        final int[] quantities;
        final long cost;
        TransactionStatus status;
        Transaction next;

        Transaction(long id, int[] quantities, long cost) {
            this.id = id;
            this.quantities = quantities;
            this.cost = cost;
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
            throw new IllegalArgumentException("prices and dispenser must not be null");
        }
        int[] copiedPrices = prices.clone();
        for (int price : copiedPrices) {
            if (price < 0) {
                throw new IllegalArgumentException("negative price");
            }
        }
        this.prices = copiedPrices;
        this.availableStock = new int[copiedPrices.length];
        this.reservedStock = new int[copiedPrices.length];
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException("invalid product or quantity");
        }
        synchronized (monitor) {
            long total = (long) availableStock[product] + reservedStock[product];
            if (quantity > Integer.MAX_VALUE - total) {
                throw new ArithmeticException("stock overflow");
            }
            availableStock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative amount");
        }
        synchronized (monitor) {
            if (amount > Long.MAX_VALUE - availableFunds - reservedFunds) {
                throw new ArithmeticException("funds overflow");
            }
            availableFunds += amount;
        }
    }

    // Insufficient resources return immediately; this method waits only for
    // monitor acquisition and for the dispenser callback to return.
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("invalid transaction or bundle");
        }
        int[] bundle = quantities.clone();
        boolean positive = false;
        long cost = 0;
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("negative quantity");
            }
            positive |= bundle[i] > 0;
            long itemCost = (long) prices[i] * bundle[i];
            if (itemCost > Long.MAX_VALUE - cost) {
                throw new IllegalArgumentException("bundle price overflow");
            }
            cost += itemCost;
        }
        if (!positive) {
            throw new IllegalArgumentException("empty bundle");
        }

        Transaction transaction = new Transaction(transactionId, bundle, cost);
        int[] callbackBundle;
        synchronized (monitor) {
            for (Transaction current = transactions; current != null; current = current.next) {
                if (current.id == transactionId) {
                    throw new IllegalArgumentException("duplicate transaction ID");
                }
            }
            boolean available = cost <= availableFunds;
            for (int i = 0; i < bundle.length; i++) {
                if (bundle[i] > availableStock[i]) {
                    available = false;
                }
            }
            if (!available) {
                transaction.status = TransactionStatus.UNAVAILABLE;
                transaction.next = transactions;
                transactions = transaction;
                return false;
            }

            callbackBundle = bundle.clone();
            transaction.status = TransactionStatus.PENDING;
            transaction.next = transactions;
            transactions = transaction;
            for (int i = 0; i < bundle.length; i++) {
                availableStock[i] -= bundle[i];
                reservedStock[i] += bundle[i];
            }
            availableFunds -= cost;
            reservedFunds += cost;
        }

        boolean dispensed = false;
        boolean committed = false;
        try {
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } finally {
            synchronized (monitor) {
                if (transaction.status == TransactionStatus.PENDING) {
                    if (dispensed) {
                        for (int i = 0; i < bundle.length; i++) {
                            reservedStock[i] -= bundle[i];
                        }
                        reservedFunds -= cost;
                        transaction.status = TransactionStatus.COMMITTED;
                        committed = true;
                    } else {
                        rollback(transaction, TransactionStatus.FAILED);
                    }
                }
            }
        }
        return committed;
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("invalid transaction ID");
        }
        synchronized (monitor) {
            for (Transaction current = transactions; current != null; current = current.next) {
                if (current.id == transactionId) {
                    if (current.status != TransactionStatus.PENDING) {
                        return false;
                    }
                    rollback(current, TransactionStatus.CANCELLED);
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            TransactionSnapshot result = null;
            for (Transaction current = transactions; current != null; current = current.next) {
                result = new TransactionSnapshot(current.id, current.status, result);
            }
            return new Snapshot(availableStock.clone(), availableFunds, result);
        }
    }

    // Called only while holding monitor.
    private void rollback(Transaction transaction, TransactionStatus status) {
        for (int i = 0; i < transaction.quantities.length; i++) {
            int quantity = transaction.quantities[i];
            reservedStock[i] -= quantity;
            availableStock[i] += quantity;
        }
        reservedFunds -= transaction.cost;
        availableFunds += transaction.cost;
        transaction.status = status;
    }
}
