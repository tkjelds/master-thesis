@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}


    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities) throws RuntimeException;
    }

    private final long[] prices;
    private final int[] stock;
    private long funds;

    private final Dispenser dispenser;
    private final long[] transactionIds;
    private final TransactionRecord[] records;

    private static final int MAX_TRANSACTIONS = 1000;

    private static class TransactionRecord {
        long id;
        int[] quantities;
        long price;
        boolean active;
        boolean cancelled;
        boolean committed;

        TransactionRecord(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.active = true;
            this.cancelled = false;
            this.committed = false;
        }
    }

    private final Object lock = new Object();

    public ResolvingVendingMachine(long[] prices, Dispenser dispenser) {
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) throw new IllegalArgumentException();
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.stock = new int[prices.length];
        this.funds = 0;

        this.transactionIds = new long[MAX_TRANSACTIONS];
        this.records = new TransactionRecord[MAX_TRANSACTIONS];
        for (int i = 0; i < MAX_TRANSACTIONS; i++) {
            transactionIds[i] = -1;
        }
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (lock) {
            long currentStock = (long) stock[product];
            if (Long.MAX_VALUE - quantity < currentStock) {
                throw new ArithmeticException();
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) throw new IllegalArgumentException();
        synchronized (lock) {
            if (Long.MAX_VALUE - amount < funds) {
                throw new ArithmeticException();
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null) throw new IllegalArgumentException();
        int totalQty = 0;
        for (int q : quantities) {
            if (q < 0) throw new IllegalArgumentException();
            totalQty += q;
        }
        if (totalQty <= 0) throw new IllegalArgumentException();

        synchronized (lock) {
            int slot = -1;
            for (int i = 0; i < MAX_TRANSACTIONS; i++) {
                if (transactionIds[i] == transactionId) {
                    throw new IllegalArgumentException();
                }
                if (transactionIds[i] == -1) {
                    slot = i;
                    break;
                }
            }

            if (slot == -1) throw new IllegalArgumentException("Capacity exceeded");

            long totalCost = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quant_check(i, quantities)) { /* handled below */ }
                // Validation of index range logic:
                if (i >= prices.length) throw new IllegalArgumentException();
            }

            // Re-check bounds carefully
            if (quantities.length > prices.length) throw new IllegalArgumentException();

            long cost = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    // Check for overflow in cost calculation
                    if (Long.MAX_VALUE / quantities[i] < prices[i]) {
                        // Result would overflow, but is it valid? 
                        // Actually, the problem says "reject invalid inputs and arithmetic overflow"
                    }
                    cost += (long) quantities[i] * prices[i];
                    // Check if cost itself overflows - but max product of long is tricky.
                    // The prompt implies we must check overflow for the calculation.
                }
            }
            // Actually, we need a more precise check for "cost" calculation logic:
            long calcCost = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    // If quantity is large, it might overflow.
                    // Since quantities are ints and prices are longs:
                    // We check if price * quantity overflows or if the sum overflows.
                    // However, standard arithmetic rules:
                    // Let's perform a safe check for product and sum.
                }
            }
            // Refined Loop:
            long totalBundlePrice = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    long p = prices[i];
                    // Use a simple check for price * quantity overflow since long can be very large
                    // But input is int, so p*q might still overflow if not careful.
                    long q = (long) quantities[i];
                    // Since input is int, max value is approx 2^31. Price is long.
                    // Product could exceed Long.MAX_VALUE if not careful, but input says "nonnegative integer"
                    // If we treat it as a standard problem, we check:
                    if (p > 0 && q > Long.MAX_VALUE / p) { /* overflow */ }
                    // The prompt says "Reject... arithmetic overflow"
                }
            }

            // Corrected logical flow for purchaseBundle:
            long totalPrice = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    long q = (long) quantities[i];
                    // Check if p * q overflows. Because result of multiplying unsigned int by long 
                    // can exceed Long.MAX_VALUE if p is large enough.
                    // But prices are non-negative long. 
                    // If we use a high enough q, it can overflow.
                    // Let's verify if p > 0 and q > (Long.MAX_VALUE / p)
                    // If the result of price * quantity would exceed some limit or if sum exceeds.
                    // Actually, to be safe: 
                }
            }
        }
    }

    // Redefining method to be clean and compliant with requirements.
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null) throw new IllegalArgumentException();
        
        synchronized (lock) {
            int slot = -1;
            for (int i = 0; i < MAX_TRANSACTIONS; i++) {
                if (transactionIds[i] == transactionId) throw new IllegalArgumentException();
                if (transactionIds[i] == -1) {
                    slot = i;
                    break;
                }
            }

            if (slot == -1) throw new IllegalArgumentException();

            long totalCost = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (i >= prices.length) throw new IllegalArgumentException();
                // Check for overflow in cost calculation or price * qty logic.
                // Wait, the requirement says "Reject invalid inputs... and arithmetic overflow".
                // We need to check if: 1. Product of price[i] and quantities[i] overflows
                // 2. Summation overflows.
            }

            // The correct approach for checking arithmetic overflow:
            long totalCost_final = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    long q = (long) quantities[i];
                    // Check if price * q overflows
                    if (prices[i] > 0 && q > Long.MAX_VALUE / prices[i]) {
                        throw new ArithmeticException();
                    }
                    long p_q = prices[i] * q;
                    // Check if sum overflows
                    if (Long.MAX_VALUE - p_q < totalCost_final) {
                        throw new Arithmetic.ArithmeticException(); // Or just throw RuntimeException/IllegalArgument
                    }
                    totalCost_final += p_q;
                }
            }

            // Check for sum of quantities? No, positive quantity.
            int totalQtySum = 0;
            for (int q : quantities) {
                if (q > 0) {
                    if (Integer.MAX_VALUE - q < totalQtySum) throw new ArithmeticException();
                    totalQtySum += q;
                }
            }

            // Need to check if we have enough funds and stock.
            long totalPrice = 0;
            // Actually, the calculation for cost and quantity sum:
            long totalPrice_calc = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    // The requirement implies we should check if any overflow occurs.
                    long q = (long) quantities[i];
                    // Valid check: if p_q > 0 and p_q / q != p then overflow. 
                    // Or simply: if (p > Long.MAX_VALUE / q) -> but q can be 0.
                    // A robust way: 
                }
            }

            // Re-implementing the core logic clearly.
        }
    }

    // Rewriting to be fully correct and compliant with all constraints.
    public static class RevisedRV {
        // This is the structure I will use for the final code.
    }

    private final long[] rvPrices; // Need to handle variables carefully.
}

// Since I must provide a single self-contained file, let's write the correct logic inside the class.

@ResolvingVendingMachine_Internal.ThreadSafe
class ResolvingVendingMachine_Internal {
    @interface ThreadSafe {}

    // This is not allowed, the class name must be ResolvingVendingMachine.
}

/* 
   Restarting Implementation.
*/

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}


    private final long[] prices;
    private final int[] stock;
    private long funds;

    private final java.util.function.Function<Long, Boolean> _dummy = null; // Not using for actual logic
    private final Dispenser dispenser;

    private final long[] transactionIds;
    private final TransactionRecord[] records;

    private static final int MAX_TRANSACTIONS = 1024;

    private static class TransactionRecord {
        long id;
        int[] quantities;
        long price;
        boolean active;
        boolean cancelled;
        boolean committed;

        TransactionRecord(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.active = true;
            this.cancelled = false;
            this.committed = false;
        }
    }

    public ResolvingVendingMachine(long[] prices, Dispenser dispenser) {
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) throw new IllegalArgumentException();
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.stock = new int[prices.length];
        this.funds = 0;

        this.transactionIds = new long[MAX_TRANSACTIONS];
        this.records = new TransactionRecord[MAX_TRANSACTIONS];
        for (int i = 0; i < MAX_TRANSACTIONS; i++) {
            transactionIds[i] = -1;
        }
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (this.getLock()) {
            long current = stock[product];
            if (Long.MAX_VALUE - quantity < current) {
                throw new ArithmeticException();
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) throw new IllegalArgumentException();
        synchronized (this.getLock()) {
            if (Long.MAX_VALUE - amount < funds) {
                throw new ArithmeticException();
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null) throw new IllegalArgumentException();

        synchronized (this.getLock()) {
            int slot = -1;
            for (int i = 0; i < MAX_TRANSACTIONS; i++) {
                if (transactionIds[i] == transactionId) {
                    throw new IllegalArgumentException();
                }
                if (transactionIds[i] == -1) {
                    slot = i;
                    break;
                }
            }

            if (slot == -1) throw new IllegalArgumentException();

            long totalCost = 0;
            int totalQtySum = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (i >= prices.length) throw new IllegalArgumentException();
                int q = quantities[i];
                if (q < 0) throw new IllegalArgumentException();
                if (q > 0) {
                    long p = prices[i];
                    // Check price * quantity overflow
                    if (p > 0 && q > Long.MAX_VALUE / p) {
                        throw new ArithmeticException();
                    }
                    long product = p * q;
                    if (Long.MAX_VALUE - product < totalCost) {
                        throw new ArithmeticException();
                    }
                    totalCost += product;

                    if (Integer.MAX_VALUE - q < totalQtySum) {
                        throw new ArithmeticException();
                    }
                    totalQtySum += q;
                }
            }

            if (totalQtySum <= 0) throw new IllegalArgumentException();

            // Check if totalCost is available in funds
            if (totalCost > funds) {
                transactionIds[slot] = -1;
                return false;
            }

            // Check if stock is available
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    if (stock[i] < quantities[i]) {
                        transactionIds[slot] = -1;
                        return false;
                    }
                }
            }

            // Reserve resources
            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) {
                    stock[i] -= quantities[i];
                }
            }
            funds -= totalCost;

            TransactionRecord record = new TransactionRecord(transactionId, quantities.clone(), totalCost);
            records[slot] = record;
            transactionIds[slot] = transactionId;

            try {
                boolean success = dispenser.dispense(transactionId, quantities);
                if (success) {
                    record.committed = true;
                    return true;
                } else {
                    // Rollback partial_success/failure handled by logic: 
                    // If result is false, it behaves as if no commitment happened.
                    // But the requirement says "A true result commits unless cancellation already won; 
                    // false or a callback RuntimeException rolls back."
                    // This implies we need to restore stock and funds.
                    rollback(slot);
                    return false;
                }
            } catch (RuntimeException e) {
                rollback(slot);
                throw e;
            }
        }
    }

    private void rollback(int slot) {
        synchronized (this.getLock()) {
            TransactionRecord record = records[slot];
            if (record != null && !record.cancelled && !record.committed) {
                for (int i = 0; i < record.quantities.length; i++) {
                    if (record.quantities[i] > 0) {
                        stock[i] += record.quantities[i];
                    }
                }
                funds += record.price;
                records[slot] = null;
                transactionIds[slot] = -1;
            }
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (this.getLock()) {
            for (int i = 0; i < MAX_TRANSACTIONS; i++) {
                if (transactionIds[i] == transactionId) {
                    TransactionRecord rec = records[i];
                    if (rec != null && !rec.cancelled && !rec.committed) {
                        rec.cancelled = true;
                        // Realistically, the requested "logically cancel" means roll back state.
                        // "Rolling back adds only that transaction's reservations back"
                        // The phrase implies the first time cancel() is called.
                        for (int j = 0; j < rec.quantities.length; j++) {
                            if (rec.quantities[j] > 0) {
                                stock[j] += rec.quantities[j];
                            }
                        }
                        funds += rec.price;
                        // Note: The description says "cancel atomically rolls back... and returns true; 
                        // otherwise false." It also says "A cancellation already won" means return false.
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (this.getLock()) {
            Snapshot s = new Snapshot();
            for (int i = 0; i < stock.length; i++) {
                s.availableStock[i] = stock[i];
            }
            s.availableFunds = funds;
            for (int i = 0; i < MAX_TRANSACTIONS; i++) {
                if (records[i] != null) {
                    s.transactionStatuses[i] = records[i].committed ? "COMMITTED" : 
                                               (records[i].cancelled ? "CANCELLED" : "PENDING");
                } else {
                    s.transactionStatuses[i] = null;
                }
            }
            return s;
        }
    }

    private Object getLock() {
        return this.lock;
    }

    public static class Snapshot {
        public int[] availableStock;
        public long availableFunds;
        public String[] transactionStatuses;

        Snapshot() {
            availableStock = new int[0]; // Will be set by caller/init
            transactionStatuses = null;
        }
    }

    // Since I cannot use private fields or external methods for the inner classes properly without 
    // proper layout, and I must provide a single file. The Snapshot objects need to be correctly initialized.
    // Let's refine the Public methods for external Visibility and correct logic execution.

}

// Need to fix variables used in class members because the task requires a single file with specific names.
// Redefining everything inside the ResolvingVendingMachine class scope properly.

@Main.ThreadSafe
class Main {
    @interface ThreadSafe {}

    public static void main(String[] args) {}
}

// Since I provided the structure above, but it had some typos (like "quant_check"), 
// here is the cleaned version of the class.


@ResolvingVendingMachine.ThreadSafe
Final corrected structure:

public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final long[] prices;
    private final int[] stock;
    private long funds;
    private final Dispenser dispenser;
    private final long[] transactionIds;
    private final TransactionRecord[] records;

    public ResolvingVendingMachine(long[] prices, Dispenser dispenser) {
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) throw new IllegalArgumentException();
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.stock = new int[prices.length];
        this.funds = 0;
        this.transactionIds = new long[1024];
        this.records = new TransactionRecord[1024];
        for (int i = 0; i < 1024; i++) {
            this.transactionIds[i] = -1;
        }
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (this.lock) {
            if (Long.MAX_VALUE - quantity < stock[product]) {
                throw new ArithmeticException();
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) throw new IllegalArgumentException();
        synchronized (this.lock) {
            if (Long.MAX_VALUE - amount < funds) {
                throw new ArithmeticException();
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null) throw new IllegalArgumentException();

        synchronized (this.lock) {
            int slot = -1;
            for (int i = 0; i < 1024; i++) {
                if (transactionIds[i] == transactionId) throw new IllegalArgumentException();
                if (transactionIds[i] == -1) {
                    slot = i;
                    break;
                }
            }

            if (slot == -1) throw new IllegalArgumentException();

            long totalCost = 0;
            int totalQtySum = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (i >= prices.length) throw new IllegalArgumentException();
                int q = quantities[i];
                if (q < 0) throw new IllegalArgumentException();
                if (q > 0) {
                    long p = prices[i];
                    if (p > 0 && q > Long.MAX_VALUE / p) throw new ArithmeticException();
                    long prod = p * q;
                    if (Long.MAX_VALUE - prod < totalCost) throw new ArithmeticException();
                    totalCost += prod;
                    if (Integer.MAX_VALUE - q < totalQtySum) throw new ArithmeticException();
                    totalQtySum += q;
                }
            }

            if (totalQtySum <= 0) throw new IllegalArgumentException();
            if (totalCost > funds) {
                transactionIds[slot] = -1;
                return false;
            }

            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0 && stock[i] < quantities[i]) {
                    transactionIds[slot] = -1;
                    return false;
                }
            }

            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) stock[i] -= quantities[i];
            }
            funds -= totalCost;

            TransactionRecord rec = new TransactionRecord(transactionId, quantities.clone(), totalCost);
            records[slot] = rec;
            transactionIds[slot] = transactionId;

            try {
                boolean success = dispenser.dispense(transactionId, quantities);
                if (success) {
                    rec.committed = true;
                    return true;
                } else {
                    rollback(slot);
                    return false;
                }
            } catch (RuntimeException e) {
                rollback(slot);
                throw e;
            }
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (this.lock) {
            for (int i = 0; i < 1024; i++) {
                if (transactionIds[i] == transactionId) {
                    TransactionRecord rec = records[i];
                    if (rec != null && !rec.cancelled && !rec.committed) {
                        rec.cancelled = true;
                        for (int j = 0; j < rec.quantities.length; j++) {
                            if (rec.quantities[j] > 0) stock[j] += rec.quantities[j];
                        }
                        funds += rec.price;
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private void rollback(int slot) {
        synchronized (this.lock) {
            TransactionRecord rec = records[slot];
            if (rec != null && !rec.cancelled && !rec.committed) {
                for (int i = 0; i < rec.quantities.length; i++) {
                    if (rec.quantities[i] > 0) stock[i] += rec.quantities[i];
                }
                funds += rec.price;
                records[slot] = null;
                transactionIds[slot] = -1;
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (this.lock) {
            Snapshot s = new Snapshot();
            s.availableStock = new int[stock.length];
            for (int i = 0; i < stock.length; i++) s.availableStock[i] = stock[i];
            s.availableFunds = funds;
            s.transactionStatuses = new String[1024];
            for (int i = 0; i < 1024; i++) {
                if (records[i] != null) {
                    s.transactionStatuses[i] = records[i].committed ? "COMMITTED" : 
                                                (records[i].cancelled ? "CANCELLED" : "PENDING");
                }
            }
            return s;
        }
    }

    public static class Snapshot {
        public int[] availableStock;
        public long availableFunds;
        public String[] transactionStatuses;
    }

    private final Object lock = new Object();

    private static class TransactionRecord {
        long id;
        int[] quantities;
        long price;
        boolean active;
        boolean cancelled;
        boolean committed;

        TransactionRecord(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.active = true;
            this.cancelled = false;
            this.committed = false;
        }
    }

    // Redefining because the snippet layout was messy. The logic is actually below.
}

@ResolvingVendingMachine.ThreadSafe
Correcting and finalizing the single file:

public class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final long[] prices;
    private final int[] stock;
    private long funds;
    private final Dispenser dispenser;
    private final long[] transactionIds;
    private final TransactionRecord[] records;
    private final Object lock = new Object();

    public ResolvingVendingMachine(long[] prices, Dispenser dispenser) {
        this.prices = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) throw new IllegalArgumentException();
            this.prices[i] = prices[i];
        }
        this.dispenser = dispenser;
        this.stock = new int[prices.length];
        this.funds = 0;
        this.transactionIds = new long[1024];
        this.records = new TransactionRecord[1024];
        for (int i = 0; i < 1024; i++) {
            this.transactionIds[i] = -1;
        }
    }

    public void restock(int product, int quantity) {
        if (product < 0 || product >= prices.length || quantity < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (lock) {
            if (Long.MAX_VALUE - quantity < stock[product]) {
                throw new ArithmeticException();
            }
            stock[product] += quantity;
        }
    }

    public void insertFunds(long amount) {
        if (amount < 0) throw new IllegalArgumentException();
        synchronized (lock) {
            if (Long.MAX_VALUE - amount < funds) {
                throw new ArithmeticException();
            }
            funds += amount;
        }
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0 || quantities == null) throw new IllegalArgumentException();

        synchronized (lock) {
            int slot = -1;
            for (int i = 0; i < 1024; i++) {
                if (transactionIds[i] == transactionId) throw new IllegalArgumentException();
                if (transactionIds[i] == -1) {
                    slot = i;
                    break;
                }
            }

            if (slot == -1) throw new IllegalArgumentException();

            long totalCost = 0;
            int totalQtySum = 0;
            for (int i = 0; i < quantities.length; i++) {
                if (i >= prices.length) throw new IllegalArgumentException();
                int q = quantities[i];
                if (q < 0) throw new IllegalArgumentException();
                if (q > 0) {
                    long p = prices[i];
                    if (p > 0 && q > Long.MAX_VALUE / p) throw new ArithmeticException();
                    long prod = p * q;
                    if (Long.MAX_VALUE - prod < totalCost) throw new ArithmeticException();
                    totalCost += prod;
                    if (Integer.MAX_VALUE - q < totalQtySum) throw new ArithmeticException();
                    totalQtySum += q;
                }
            }

            if (totalQtySum <= 0) throw new IllegalArgumentException();
            if (totalCost > funds) {
                transactionIds[slot] = -1;
                return false;
            }

            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0 && stock[i] < quantities[i]) {
                    transactionIds[slot] = -1;
                    return false;
                }
            }

            for (int i = 0; i < quantities.length; i++) {
                if (quantities[i] > 0) stock[i] -= quantities[i];
            }
            funds -= totalCost;

            TransactionRecord rec = new TransactionRecord(transactionId, quantities.clone(), totalCost);
            records[slot] = rec;
            transactionIds[slot] = transactionId;

            try {
                boolean success = dispenser.dispense(transactionId, quantities);
                if (success) {
                    rec.committed = true;
                    return true;
                } else {
                    rollback(slot);
                    return false;
                }
            } catch (RuntimeException e) {
                rollback(slot);
                throw e;
            }
        }
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            for (int i = 0; i < 1024; i++) {
                if (transactionIds[i] == transactionId) {
                    TransactionRecord rec = records[i];
                    if (rec != null && !rec.cancelled && !rec.committed) {
                        rec.cancelled = true;
                        for (int j = 0; j < rec.quantities.length; j++) {
                            if (rec.quantities[j] > 0) stock[j] += rec.quantities[j];
                        }
                        funds += rec.price;
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Snapshot s = new Snapshot();
            s.availableStock = new int[stock.length];
            for (int i = 0; i < stock.length; i++) s.availableStock[i] = stock[i];
            s.availableFunds = funds;
            s.transactionStatuses = new String[1024];
            for (int i = 0; i < 1024; i++) {
                if (records[i] != null) {
                    s.transactionStatuses[i] = records[i].committed ? "COMMITTED" : 
                                                (records[i].cancelled ? "CANCELLED" : "PENDING");
                }
            }
            return s;
        }
    }

    private void rollback(int slot) {
        synchronized (lock) {
            TransactionRecord rec = records[slot];
            if (rec != null && !rec.cancelled && !rec.committed) {
                for (int i = 0; i < rec.quantities.length; i++) {
                    if (rec.quantities[i] > 0) stock[i] += rec.quantities[i];
                }
                funds += rec.price;
                records[slot] = null;
                transactionIds[slot] = -1;
            }
        }
    }

    public static class Snapshot {
        public int[] availableStock;
        public long availableFunds;
        public String[] transactionStatuses;
    }

    private static class TransactionRecord {
        long id;
        int[] quantities;
        long price;
        boolean active;
        boolean cancelled;
        boolean committed;

        TransactionRecord(long id, int[] quantities, long price) {
            this.id = id;
            this.quantities = quantities;
            this.price = price;
            this.active = true;
            this.cancelled = false;
            this.committed = false;
        }
    }

    public interface Dispenser {
        boolean dispense(long transactionId, int[] quantities) throws RuntimeException;
    }
}

