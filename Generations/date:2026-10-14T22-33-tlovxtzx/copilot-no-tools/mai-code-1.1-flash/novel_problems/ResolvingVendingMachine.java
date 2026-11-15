@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum State {
        PENDING,
        COMMITTED,
        CANCELLED,
        ROLLED_BACK
    }

    public static final class TransactionStatus {
        public final long transactionId;
        public final State state;
        public final int[] bundle;
        public final long reservedAmount;
        public final boolean pending;
        public final boolean committed;
        public final boolean cancelled;
        public final boolean rolledBack;
        public final boolean canceled;

        public TransactionStatus(long transactionId, State state, int[] bundle, long reservedAmount) {
            this.transactionId = transactionId;
            this.state = state;
            this.bundle = (bundle == null) ? new int[0] : bundle.clone();
            this.reservedAmount = reservedAmount;
            this.pending = (state == State.PENDING);
            this.committed = (state == State.COMMITTED);
            this.cancelled = (state == State.CANCELLED);
            this.rolledBack = (state == State.ROLLED_BACK);
            this.canceled = this.cancelled;
        }
    }

    public static final class Snapshot {
        public final int[] prices;
        public final int[] stock;
        public final int[] availableStock;
        public final long funds;
        public final long availableFunds;
        public final TransactionStatus[] transactions;
        public final TransactionStatus[] transactionStatuses;

        public Snapshot(int[] prices, int[] stock, long funds, TransactionStatus[] transactions) {
            this.prices = (prices == null) ? new int[0] : prices.clone();
            this.stock = (stock == null) ? new int[0] : stock.clone();
            this.availableStock = this.stock.clone();
            this.funds = funds;
            this.availableFunds = funds;
            this.transactions = (transactions == null) ? new TransactionStatus[0] : transactions.clone();
            this.transactionStatuses = this.transactions.clone();
        }

        public int[] getPrices() {
            return prices.clone();
        }

        public int[] getStock() {
            return stock.clone();
        }

        public int[] getAvailableStock() {
            return availableStock.clone();
        }

        public long getFunds() {
            return funds;
        }

        public long getAvailableFunds() {
            return availableFunds;
        }

        public TransactionStatus[] getTransactions() {
            return transactions.clone();
        }
    }

    private static final class TransactionRecord {
        final long id;
        final int[] bundle;
        final long amount;
        State state;

        TransactionRecord(long id, int[] bundle, long amount) {
            this.id = id;
            this.bundle = bundle.clone();
            this.amount = amount;
            this.state = State.PENDING;
        }

        TransactionStatus snapshotStatus() {
            return new TransactionStatus(id, state, bundle, amount);
        }
    }

    private final Object monitor;
    private final Dispenser dispenser;
    private final int[] prices;
    private final int[] stock;
    private long funds;
    private TransactionRecord[] transactions;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        if (dispenser == null) {
            throw new IllegalArgumentException("dispenser must not be null");
        }
        this.monitor = new Object();
        this.prices = new int[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("price must be nonnegative");
            }
            this.prices[i] = prices[i];
        }
        this.stock = new int[prices.length];
        this.funds = 0L;
        this.transactions = new TransactionRecord[8];
        this.transactionCount = 0;
        this.dispenser = dispenser;
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("invalid product index");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be nonnegative");
        }

        synchronized (monitor) {
            long reserved = reservedStockForProduct(product);
            long totalStock = (long) stock[product] + reserved;
            if (quantity > Integer.MAX_VALUE - totalStock) {
                throw new IllegalArgumentException("stock overflow");
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }

        synchronized (monitor) {
            long reservedFunds = reservedFundsTotal();
            long totalFunds = funds + reservedFunds;
            if (amount > Long.MAX_VALUE - totalFunds) {
                throw new IllegalArgumentException("funds overflow");
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0L) {
            throw new IllegalArgumentException("transactionId must be positive");
        }
        if (quantities == null) {
            throw new IllegalArgumentException("quantities must not be null");
        }
        if (quantities.length != prices.length) {
            throw new IllegalArgumentException("quantity count does not match product count");
        }

        int[] bundle = new int[quantities.length];
        int positiveCount = 0;
        long totalCost = 0L;
        for (int i = 0; i < quantities.length; i++) {
            int q = quantities[i];
            if (q < 0) {
                throw new IllegalArgumentException("bundle quantities must be nonnegative");
            }
            bundle[i] = q;
            if (q > 0) {
                positiveCount++;
            }
            long productCost = (long) prices[i] * (long) q;
            if (totalCost > Long.MAX_VALUE - productCost) {
                throw new IllegalArgumentException("bundle cost overflow");
            }
            totalCost += productCost;
        }
        if (positiveCount == 0) {
            throw new IllegalArgumentException("bundle must contain at least one positive quantity");
        }

        final TransactionRecord record;
        synchronized (monitor) {
            if (containsTransactionId(transactionId)) {
                throw new IllegalArgumentException("duplicate transactionId");
            }

            if (!bundleAvailable(bundle)) {
                return false;
            }
            if (totalCost > funds) {
                return false;
            }

            record = new TransactionRecord(transactionId, bundle, totalCost);
            addTransaction(record);

            for (int i = 0; i < bundle.length; i++) {
                stock[i] -= bundle[i];
            }
            funds -= totalCost;
        }

        final int[] detached = bundle.clone();
        boolean callbackResult;
        try {
            callbackResult = dispenser.dispense(transactionId, detached);
        } catch (RuntimeException ex) {
            synchronized (monitor) {
                TransactionRecord pending = findRecord(transactionId);
                if (pending != null && pending.state == State.PENDING) {
                    rollbackReservation(pending);
                    pending.state = State.ROLLED_BACK;
                }
            }
            throw ex;
        }

        synchronized (monitor) {
            TransactionRecord pending = findRecord(transactionId);
            if (pending == null || pending.state != State.PENDING) {
                return false;
            }
            if (callbackResult) {
                pending.state = State.COMMITTED;
                return true;
            } else {
                rollbackReservation(pending);
                pending.state = State.ROLLED_BACK;
                return false;
            }
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0L) {
            return false;
        }

        synchronized (monitor) {
            TransactionRecord record = findRecord(transactionId);
            if (record == null || record.state != State.PENDING) {
                return false;
            }

            rollbackReservation(record);
            record.state = State.CANCELLED;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            TransactionStatus[] statuses = new TransactionStatus[transactionCount];
            for (int i = 0; i < transactionCount; i++) {
                statuses[i] = transactions[i].snapshotStatus();
            }
            return new Snapshot(prices, stock, funds, statuses);
        }
    }

    private boolean containsTransactionId(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].id == transactionId) {
                return true;
            }
        }
        return false;
    }

    private TransactionRecord findRecord(long transactionId) {
        for (int i = 0; i < transactionCount; i++) {
            if (transactions[i].id == transactionId) {
                return transactions[i];
            }
        }
        return null;
    }

    private void addTransaction(TransactionRecord record) {
        if (transactionCount == transactions.length) {
            TransactionRecord[] expanded = new TransactionRecord[transactions.length * 2];
            System.arraycopy(transactions, 0, expanded, 0, transactionCount);
            transactions = expanded;
        }
        transactions[transactionCount] = record;
        transactionCount++;
    }

    private boolean bundleAvailable(int[] bundle) {
        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] > stock[i]) {
                return false;
            }
        }
        return true;
    }

    private void rollbackReservation(TransactionRecord record) {
        for (int i = 0; i < record.bundle.length; i++) {
            stock[i] += record.bundle[i];
        }
        funds += record.amount;
    }

    private long reservedStockForProduct(int product) {
        long reserved = 0L;
        for (int i = 0; i < transactionCount; i++) {
            TransactionRecord record = transactions[i];
            if (record.state == State.PENDING) {
                reserved += record.bundle[product];
            }
        }
        return reserved;
    }

    private long reservedFundsTotal() {
        long reserved = 0L;
        for (int i = 0; i < transactionCount; i++) {
            TransactionRecord record = transactions[i];
            if (record.state == State.PENDING) {
                reserved += record.amount;
            }
        }
        return reserved;
    }
}
