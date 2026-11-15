@ResolvingVendingMachine.ThreadSafe
public final class ResolvingVendingMachine {
    @interface ThreadSafe {}

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities);
    }

    public enum Status {
        PENDING, COMMITTED, REJECTED, CANCELLED, ROLLED_BACK
    }

    public static final class Snapshot {
        public final int[] availableStock;
        public final long availableFunds;
        public final long[] transactionIds;
        public final Status[] statuses;

        private Snapshot(int[] stock, long funds, long[] ids, Status[] states) {
            availableStock = stock;
            availableFunds = funds;
            transactionIds = ids;
            statuses = states;
        }
    }

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
        }
    }

    private final Object monitor = new Object();
    private final int[] prices;
    private final int[] availableStock;
    private final int[] reservedStock;
    private final Dispenser dispenser;

    private long availableFunds;
    private long reservedFunds;
    private Transaction first;
    private Transaction last;
    private int transactionCount;

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        if (prices == null || dispenser == null) {
            throw new IllegalArgumentException("Prices and dispenser must not be null");
        }

        this.prices = copy(prices);
        for (int i = 0; i < this.prices.length; i++) {
            if (this.prices[i] < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative");
            }
        }

        this.dispenser = dispenser;
        availableStock = new int[this.prices.length];
        reservedStock = new int[this.prices.length];
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length) {
            throw new IllegalArgumentException("Invalid product");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity must be nonnegative");
        }

        synchronized (monitor) {
            // Reserve enough capacity for every pending rollback.
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

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }
        if (quantities == null || quantities.length != prices.length) {
            throw new IllegalArgumentException("One quantity per product is required");
        }

        int[] bundle = copy(quantities);
        boolean nonempty = false;
        long price = 0;

        for (int i = 0; i < bundle.length; i++) {
            if (bundle[i] < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
            if (bundle[i] > 0) {
                nonempty = true;
            }

            long itemPrice = (long) prices[i] * bundle[i];
            if (itemPrice > Long.MAX_VALUE - price) {
                throw new ArithmeticException("Bundle price overflow");
            }
            price += itemPrice;
        }

        if (!nonempty) {
            throw new IllegalArgumentException("Bundle must not be empty");
        }

        // Allocate detached callback data before reserving any resources.
        int[] callbackBundle = copy(bundle);
        Transaction transaction = new Transaction(transactionId, bundle, price);

        synchronized (monitor) {
            if (find(transactionId) != null) {
                throw new IllegalArgumentException("Duplicate transaction ID");
            }
            if (transactionCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Transaction history is full");
            }

            boolean available = availableFunds >= price;
            for (int i = 0; i < bundle.length; i++) {
                if (availableStock[i] < bundle[i]) {
                    available = false;
                }
            }

            transaction.status = available ? Status.PENDING : Status.REJECTED;
            if (last == null) {
                first = transaction;
            } else {
                last.next = transaction;
            }
            last = transaction;
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

        boolean dispensed;
        try {
            // Cancellation is logical; it cannot prevent or stop this callback.
            dispensed = dispenser.dispense(transactionId, callbackBundle);
        } catch (RuntimeException | Error failure) {
            synchronized (monitor) {
                if (transaction.status == Status.PENDING) {
                    finish(transaction, Status.ROLLED_BACK);
                }
            }
            throw failure;
        }

        synchronized (monitor) {
            if (transaction.status != Status.PENDING) {
                return false;
            }

            finish(transaction, dispensed ? Status.COMMITTED : Status.ROLLED_BACK);
            return dispensed;
        }
    }

    public boolean cancel(long transactionId) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive");
        }

        synchronized (monitor) {
            Transaction transaction = find(transactionId);
            if (transaction == null || transaction.status != Status.PENDING) {
                return false;
            }

            finish(transaction, Status.CANCELLED);
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int[] stock = copy(availableStock);
            long[] ids = new long[transactionCount];
            Status[] states = new Status[transactionCount];

            Transaction transaction = first;
            int index = 0;
            while (transaction != null) {
                ids[index] = transaction.id;
                states[index] = transaction.status;
                index++;
                transaction = transaction.next;
            }

            return new Snapshot(stock, availableFunds, ids, states);
        }
    }

    // Requires monitor ownership.
    private Transaction find(long transactionId) {
        Transaction transaction = first;
        while (transaction != null) {
            if (transaction.id == transactionId) {
                return transaction;
            }
            transaction = transaction.next;
        }
        return null;
    }

    // Requires monitor ownership and a pending transaction.
    private void finish(Transaction transaction, Status status) {
        boolean refund = status != Status.COMMITTED;

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
        transaction.status = status;
    }

    private static int[] copy(int[] source) {
        int[] result = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
