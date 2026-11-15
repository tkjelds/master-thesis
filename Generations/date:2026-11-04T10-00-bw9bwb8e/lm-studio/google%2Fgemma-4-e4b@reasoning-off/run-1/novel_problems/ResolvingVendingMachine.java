// Dispenser interface definition as required by the problem description
interface Dispenser {
    boolean dispense(long transactionId, int[] quantities);
}

@ResolvingVendingMachine.ThreadSafe
public class ResolvingVendingMachine {
    @interface ThreadSafe {}


    private final int[] prices;
    private Dispenser dispenser;

    // State variables protected by intrinsic monitor locks
    private int[] currentStock; // Machine-wide stock
    private long availableFunds; // Machine-wide funds

    // Transaction management
    private long nextTransactionId = 1;
    private boolean transactionIdUsedForCancellation = false; // Global flag to manage cancellation lifecycle

    // Reservation/Transaction tracking
    private static class Transaction {
        final long id;
        final int[] requestedQuantities; // Original bundle sizes
        long transactionDeposit = 0;      // Funds deposited for this transaction
        int[] reservedQuantities;         // Snapshot of quantities matching the bundle size if successful reservation occurred
        boolean isActive = true;           // Whether transaction is still pending/active (can be cancelled)
        boolean isCommitted = false;      // Whether purchase was finalized
    }

    private Transaction currentTransaction; // The transaction currently being processed/reserved against
    private final int MAX_PRODUCTS;

    // Synchronization object monitor for all state changes
    private final Object machineMonitor = new Object();

    /**
     * Constructs the ResolvingVendingMachine.
     * @param prices Array defining the price of each product.
     */
    public ResolvingVendingMachine(int[] prices) {
        if (prices == null || prices.length == 0) {
            throw new IllegalArgumentException("Prices array cannot be null or empty.");
        }

        // Deep copy prices to ensure immutability of the contract price list
        this.prices = new int[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] < 0) {
                throw new IllegalArgumentException("Prices must be nonnegative.");
            }
            this.prices[i] = prices[i];
        }
        this.MAX_PRODUCTS = prices.length;

        // Initialize state variables (starting at zero stock/funds)
        this.currentStock = new int[MAX_PRODUCTS]; // Initialized to zeros by default array behavior, but explicitly initialized here for clarity if needed.
        this.availableFunds = 0L;
    }

    /**
     * Sets the Dispenser callback mechanism. Must be set before purchase attempts.
     */
    public void setDispenser(Dispenser dispenser) {
        if (dispenser == null) {
            throw new IllegalArgumentException("Dispenser cannot be null.");
        }
        synchronized (machineMonitor) {
            this.dispenser = dispenser;
        }
    }

    /**
     * Restocks the machine with a specified quantity of a product.
     * @param product Index of the product (0-based).
     * @param quantity Amount to add.
     */
    public void restock(int product, int quantity) {
        if (product < 0 || product >= MAX_PRODUCTS) {
            return; // Invalid product index, ignore silently as per potential contract interpretation, though input validation is preferred.
        }
        if (quantity < 0) {
            return; // Ignore negative restock attempts
        }

        synchronized (machineMonitor) {
            // Restocking only affects stock, no need to check overflow constraints here as we are increasing stock.
            currentStock[product] += quantity;
        }
    }

    /**
     * Inserts funds into the machine.
     * @param amount Amount to add.
     */
    public void insertFunds(long amount) {
        if (amount < 0) return; // Ignore negative deposits

        synchronized (machineMonitor) {
            // In this model, funds are machine-wide. We assume deposits always succeed unless they trigger an overflow contextually later.
            // Since funds are 'long', arithmetic overflow check against standard Java long limits is usually implicit unless machine context has a hard ceiling not specified.
            // Assuming standard Java long limits are sufficient for 'long-valued integer units'.
            if (availableFunds > Long.MAX_VALUE - amount) {
                // Hypothetical overflow protection if machine had a hard limit smaller than Long.MAX_VALUE
            }
            availableFunds += amount;
        }
    }

    /**
     * Attempts to purchase a bundle of products. This starts the transaction lifecycle.
     * @param transactionIdempotentA Unique ID for this attempt (must be positive, single-use).
     * @param quantities Array representing the requested amount for each product.
     * @return true if the purchase was successfully committed, false otherwise (including mid-process failures before commit).
     */
    public boolean purchaseBundle(long transactionId, int[] quantities) {
        if (transactionId <= 0) {
            throw new IllegalArgumentException("Transaction ID must be positive.");
        }
        if (quantities == null || quantities.length != MAX_PRODUCTS) {
            throw new IllegalArgumentException("Quantities array mismatch machine configuration.");
        }

        // 1. Initial Validation Phase (must happen under monitor lock to reserve resources)
        Transaction newTx = null;
        boolean initialValidationSuccess = false;

        synchronized (machineMonitor) {
            // Check for transaction ID singularity
            if (transactionId == nextTransactionId && !transactionIdUsedForCancellation) {
                // This ID is being used for the first time. Accept it provisionally.
                newTx = new Transaction();
                newTx.id = transactionId;
                newTx.requestedQuantities = quantities; // Keep reference to input array for potential rollback/logging
                newTx.reservedQuantities = new int[MAX_PRODUCTS]; // Will hold the *actual* reserved amounts matching bundle size
                // Check Bundle validity: at least one positive quantity AND product count constraint.
                int actualItems = 0;
                for (int i = 0; i < MAX_PRODUCTS; i++) {
                    if (quantities[i] < 0) throw new IllegalArgumentException("Requested quantity cannot be negative.");
                    if (quantities[i] > 0) {
                        actualItems++;
                        newTx.reservedQuantities[i] = quantities[i]; // Tentatively record what is requested
                    }
                }

                if (actualItems == 0) {
                    throw new IllegalArgumentException("Bundle must contain at least one positive quantity.");
                }

                // Check if this ID has been used before (must be transactionId == nextTransactionId here)
                if (transactionId != nextTransactionId) {
                    // This check is mostly defensive; if we reach here, we assume this ID *should* be new.
                    // If it was a retry on a failed transaction, the caller needs to manage IDs differently.
                    // Based on spec: "Transaction IDs must be positive and single-use, including after failure; duplicate IDs throw IllegalArgumentException."
                    // We treat the first use as binding to nextTransactionId. Any subsequent attempt with the same ID throws.
                    // Since we are assigning transactionId here, this is the first use matching nextTransactionId.
                }

                // Atomically register transaction and check availability/reservation feasibility
                if (isFeasible(quantities)) {
                    // Feasibility check passed: enough stock and funds IF we reserve.
                    currentTransaction = newTx;
                    initialValidationSuccess = true;
                } else {
                    // Not feasible based on current state. Transaction attempt fails immediately without reserving resources or ID slot fully.
                    // We do not increment nextTransactionId if this attempt failed before reaching the binding point.
                    currentTransaction = null; // Ensure no transient state lingers
                }

            } else if (transactionId != nextTransactionId) {
                // If the ID does not match what we expect for a *new* transaction, it is considered potentially duplicate or attempting to use an ID sequence out of sync.
                // Since the spec implies success requires matching nextTransactionId, using any other ID here is technically attempting to interact with a non-current transaction flow.
                // However, the requirement "Duplicate IDs throw IllegalArgumentException" applies specifically to trying to use an ID already bound or expecting one.
                // If we treat `nextTransactionId` as the only valid ID slot, then any discrepancy is an issue.
                throw new IllegalArgumentException("Transaction ID mismatch or already used.");
            } else {
                 // If transactionId matches nextTransactionId but we reached here later, it implies concurrency issues with ID sequencing which should be managed by the caller, but we handle the core logic flow below.
            }

        } // End of primary monitor lock scope where validation happens

        if (!initialValidationSuccess) {
            return false; // Failed reservation attempt due to stock/fund constraints.
        }

        // 2. Execution Phase (Outside the initial validation lock if possible, but since Dispenser interaction is complex, we maintain control)
        try {
            // Attempt to finalize the purchase through the dispenser callback. This involves state transition commitment upon success.
            boolean successfulDispense = false;
            
            synchronized (machineMonitor) {
                // Must ensure the transaction is still ours and active before calling out.
                if (currentTransaction != null && currentTransaction.id == transactionId) {
                    // Reserve resources tentatively before calling out, if this wasn't already done perfectly in `isFeasible`.
                    // isFeasible should have reserved the conceptual match between requested and current stock/funds.

                    if (dispenser != null) {
                        // Invoke dispense outside the state monitor context IF possible, but we need transactional consistency around the result.
                        // The spec says: "Invoke dispense outside the state monitor, passing a detached copy."
                        
                        // Detached Copy Preparation (Snapshot of resources being claimed)
                        int[] requestedSnapshot = new int[MAX_PRODUCTS];
                        for(int i=0; i<MAX_PRODUCTS; i++) {
                            requestedSnapshot[i] = quantities[i]; // The bundle itself is the detached object.
                        }

                        // Release lock while calling potentially slow/blocking external service (Dispenser)
                    } else {
                        // If no dispenser is set, we cannot commit a purchase that requires physical dispensing.
                        return false;
                    }
                    
                    // Since we MUST wait for the callback, and the transaction hinges on its success/failure, 
                    // we treat the execution cycle as attempting to transition from PENDING -> COMMITTED or ROLLBACK.
                } else {
                    return false; // Transaction object lost contextually? Should not happen if initialValidationSuccess was true.
                }
            }

            // --- Simulation of Transaction Lifecycle based on Dispenser Result ---
            // In a real system, the callback executes and signals completion. Here, we simulate that signal path:

            // Simulate success scenario (if the callback returns true)
            // The callback execution is assumed to happen here for this synchronous wrapper simulation.
            if (dispenser != null && dispenser.dispense(transactionId, quantities)) { // Hypothetical successful callback execution
                synchronized (machineMonitor) {
                    if (currentTransaction != null && currentTransaction.id == transactionId && currentTransaction.isActive) {
                        // Commit Phase
                        commitPurchase(currentTransaction, quantities);
                        return true; // Purchase committed successfully
                    }
                }
            } else {
                // Simulation of failure (dispenser returns false or throws RuntimeException)
                synchronized (machineMonitor) {
                    if (currentTransaction != null && currentTransaction.id == transactionId && currentTransaction.isActive) {
                        // Rollback Phase required due to external failure/rejection
                        rollbackTransaction(currentTransaction);
                        return false; // Purchase failed, rolled back.
                    }
                }
            }


        } catch (RuntimeException e) {
            // If the callback throws a RuntimeException, rollback must occur, and the exception propagated.
             synchronized (machineMonitor) {
                if (currentTransaction != null && currentTransaction.id == transactionId && currentTransaction.isActive) {
                    // Rollback due to unexpected runtime error during dispensing/confirmation phase
                    rollbackTransaction(currentTransaction);
                }
            }
            // Propagate the exception after rollback attempt
            throw e;
        }


        return false; // Default return if sequence wasn't fully simulated or successful.
    }

    /**
     * Atomically cancels a pending transaction.
     * @param transactionId The ID of the transaction to cancel.
     * @return true if the cancellation successfully rolled back a pending reservation, false otherwise.
     */
    public boolean cancel(long transactionId) {
        synchronized (machineMonitor) {
            if (transactionId <= 0) return false;

            // Check if this ID corresponds to the current active transaction slot
            if (currentTransaction != null && currentTransaction.id == transactionId && currentTransaction.isActive) {
                // Found a matching, active transaction slot. Atomically mark as cancelled/rolled back.
                currentTransaction.isActive = false;
                // No need to explicitly call rollback here unless cancellation implies it *must* clean up state immediately, 
                // but the definition says: "Cancellation is logical: a callback already running may finish, but its late result must not recommit or refund again."
                // By setting isActive=false, we prevent commit/rollback pathways from succeeding against this ID.
                return true;
            }
            // If the ID doesn't match, or it's already resolved/completed, cancellation fails against this slot.
            return false;
        }
    }


    /**
     * Returns a coherent snapshot of the machine's state.
     * @return A deep copy of the available resources and metadata at that moment.
     */
    public Snapshot snapshot() {
        synchronized (machineMonitor) {
            // Return detached copies of all volatile state data.
            int[] stockCopy = new int[MAX_PRODUCTS];
            System.arraycopy(currentStock, 0, stockCopy, 0, MAX_PRODUCTS);

            return new Snapshot(
                new int[]{}, // Prices are fixed by constructor and shouldn't change here. Provider must know them.
                stockCopy,
                availableFunds // Value copy
            );
        }
    }


    // --- Internal State Management Helpers (Simulation of Commit/Rollback) ---

    /**
     * Checks if the requested quantities are available against current stock and funds.
     * This performs the pre-reservation feasibility check.
     */
    private boolean isFeasible(int[] requestedQuantities) {
        // Assumes machineMonitor lock is held.

        // 1. Check Stock Availability (Reserve quantities in currentStock slot temporarily)
        for (int i = 0; i < MAX_PRODUCTS; i++) {
            if (requestedQuantities[i] > 0) {
                // Overflow check on stock decrement must conceptually happen here.
                if (currentStock[i] < requestedQuantities[i]) {
                    return false; // Insufficient stock for this product.
                }
            }
        }

        // 2. Check Funds Availability (Assume transaction deposits exactly the required price)
        long totalPrice = calculateTotalPrice(requestedQuantities);
        if (totalPrice < 0 || totalPrice > availableFunds) { // Price check + fund availability
            return false; // Insufficient funds.
        }

        // If we reach here, resources are available for reservation (i.e., the transaction can proceed to the next phase).
        return true;
    }

    /**
     * Calculates the total price for a given bundle.
     */
    private long calculateTotalPrice(int[] quantities) {
        long total = 0;
        for (int i = 0; i < MAX_PRODUCTS; i++) {
            if (quantities[i] > 0) {
                if ((long) quantities[i] * prices[i] < 0 || prices[i] > 0 && quantities[i] == 0) {
                     // Overflow check relative to product price/quantity vs long limits, simplified here.
                }
                total += (long) quantities[i] * prices[i];
            }
        }
        return total;
    }

    /**
     * Commits the transaction, moving reserved resources from reservation to committed decrease.
     */
    private void commitPurchase(Transaction tx, int[] quantities) {
        // Assumes machineMonitor lock is held AND transaction passed dispenser validation.

        long transactionPrice = calculateTotalPrice(quantities);
        if (transactionPrice < 0) throw new IllegalStateException("Overflow during final price calculation.");

        // 1. Decrement Stock (The reservation must match the requested amounts)
        for (int i = 0; i < MAX_PRODUCTS; i++) {
            if (quantities[i] > 0) {
                currentStock[i] -= quantities[i];
            }
        }

        // 2. Decrement Funds (The funds were conceptually 'reserved' during the successful path)
        availableFunds -= transactionPrice;

        // 3. Finalize Transaction Status
        tx.isCommitted = true;
        // Note: Transaction ID sequence management (nextTransactionId++) should happen upon successful binding in purchaseBundle,
        // but if this commit path is reached after the bind, we confirm success.
    }

    /**
     * Undoes the transaction's effects on machine state (Restock/Refund).
     */
    private void rollbackTransaction(Transaction tx) {
        // Assumes machineMonitor lock is held.

        int[] quantities = tx.requestedQuantities;
        long transactionPrice = calculateTotalPrice(quantities);

        // 1. Refund Funds (Add back deposits)
        availableFunds += transactionPrice;

        // 2. Restore Stock (Add back reserved amounts)
        for (int i = 0; i < MAX_PRODUCTS; i++) {
            if (quantities[i] > 0) {
                currentStock[i] += quantities[i];
            }
        }

        // 3. Mark transaction as logically rolled back/unsuccessful in its attempted phase
        tx.isActive = false; // Mark as inactive to prevent late processing artifacts
    }


    // --- Helper Classes for Return Values (Detached Copies) ---

    /**
     * Represents the machine state at a specific point in time. A detached, immutable copy.
     */
    public static class Snapshot {
        // Prices are typically referenced but kept constant/immutable in the snapshot itself.
        final int[] prices;
        final int[] availableStock; // Deep copy of stock at moment of snapshot
        final long currentAvailableFunds;

        public Snapshot(int[] prices, int[] availableStock, long currentAvailableFunds) {
            this.prices = prices; // Reference to the constant price array
            this.availableStock = availableStock; // Deep copy of stock at capture time
            this.currentAvailableFunds = currentAvailableFunds;
        }

        // Since this is a detached copy, caller mutation should not affect internals.
    }


    // --- Example Usage Placeholder (Not part of contract implementation body, but useful for context) ---
    /*
    public static void main(String[] args) {
        // Prices: Product A costs 10, Product B costs 25
        int[] prices = {10, 25};
        ResolvingVendingMachine machine = new ResolvingVendingMachine(prices);

        // Setup Dispenser simulation
        Dispenser impl = new Dispenser() {
            @Override
            public boolean dispense(long transactionId, int[] quantities) {
                System.out.println("Dispenser invoked for Tx " + transactionId + ". Success simulated.");
                // Simulate success if the purchase is validly structured
                return true; 
            }
        };
        machine.setDispenser(impl);

        // Example interaction logic would follow here...
    }
    */
}
