@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private volatile long funds;
    private final TransactionManager transactions;
    private final Dispenser dispenser;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = prices.clone();
        this.funds = 0;
        this.transactions = new TransactionManager();
        this.dispenser = dispenser;
    }

    public synchronized void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) return;
        transactions.restock(product, quantity);
    }

    public synchronized boolean insertFunds(long amount) {
        if (amount < 0) return false;
        funds += amount;
        return true;
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        synchronized (this) {
            if (!isValidTransactionId(transactionId)) return false;
            Transaction transaction = new Transaction(transactionId, quantities);
            if (!transactions.register(transaction)) return false;
        }
        try {
            boolean result = dispenser.dispense(transactionId, quantities);
            if (result) transactions.commit(transaction);
            return result;
        } catch (RuntimeException e) {
            transactions.rollback(transaction);
            throw e;
        }
    }

    public synchronized boolean cancel(long transactionId) {
        if (!isValidTransactionId(transactionId)) return false;
        Transaction transaction = transactions.get(transactionId);
        if (transaction == null) return false;
        if (!transactions.isCommitted(transaction)) {
            transactions.rollback(transaction);
            return true;
        }
        return false;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(funds, transactions.getSnapshot());
    }

    private boolean isValidTransactionId(long transactionId) {
        return transactionId > 0;
    }

    private static class TransactionManager {
        private final long[] reservedStocks;
        private final boolean[] iscommitted;
        private final int[] transactionCounters;

        public TransactionManager() {
            reservedStocks = new long[1024]; // Assuming a maximum of 1024 transaction IDs
            iscommitted = new boolean[1024];
            transactionCounters = new int[1024];
        }

        public synchronized void restock(int product, int quantity) {
            for (int i = 0; i < reservedStocks.length; i++) {
                if (iscommitted[i]) continue;
                reservedStocks[i] += quantity;
            }
        }

        public synchronized boolean register(Transaction transaction) {
            int id = (int) (transaction.getTransactionId() % reservedStocks.length);
            if (iscommitted[id]) return false;
            transactionCounters[id]++;
            iscommitted[id] = true;
            for (int i = 0; i < prices.length; i++) {
                reservedStocks[id] += transaction.getQuantities()[i] * prices[i];
            }
            return true;
        }

        public synchronized boolean isCommitted(Transaction transaction) {
            int id = (int) (transaction.getTransactionId() % reservedStocks.length);
            return iscommitted[id];
        }

        public synchronized void commit(Transaction transaction) {
            int id = (int) (transaction.getTransactionId() % reservedStocks.length);
            transactionCounters[id] = 0;
            funds -= reservedStocks[id];
        }

        public synchronized void rollback(Transaction transaction) {
            int id = (int) (transaction.getTransactionId() % reservedStocks.length);
            for (int i = 0; i < prices.length; i++) {
                reservedStocks[id] -= transaction.getQuantities()[i] * prices[i];
            }
        }

        public synchronized Snapshot getSnapshot() {
            return new Snapshot(reservedStocks.clone(), iscommitted.clone());
        }
    }

    private static class Transaction {
        private final long transactionId;
        private final int[] quantities;

        public Transaction(long transactionId, int[] quantities) {
            this.transactionId = transactionId;
            this.quantities = quantities.clone();
        }

        public long getTransactionId() {
            return transactionId;
        }

        public int[] getQuantities() {
            return quantities;
        }
    }

    private static class Snapshot {
        private final long funds;
        private final boolean[] reservedStocks;

        public Snapshot(long funds, boolean[] reservedStocks) {
            this.funds = funds;
            this.reservedStocks = reservedStocks.clone();
        }

        public long getFunds() {
            return funds;
        }

        public boolean[] getReservedStocks() {
            return reservedStocks;
        }
    }
}
