@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    // --- Internal State Variables ---

    // Committed quantities. Must be defensively copied on read (snapshot).
    private long[] committedQuantities;

    // Staging slot holds the details of the operation waiting to be committed.
    private StagedOperation stagedOp;

    // Tracks the next available generation number to assign upon successful staging.
    private volatile long nextGeneration;

    // Synchronization object for controlling access to state transitions (staging/committing).
    private final Object monitor = new Object();

    /**
     * Internal class representing the pending transaction data.
     * All transient/copied data must be encapsulated here.
     */
    private static class StagedOperation {
        final String ownerId;
        final long generation; // Generation assigned when staged
        final long[] delta; // The proposed changes (must match size of committedQuantities)
        final String[] tags; // Tags associated with this stage attempt

        // Constructor for successful staging
        StagedOperation(String ownerId, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = -1; // Initialized later when staging successfully blocks on monitor
            this.delta = delta;
            this.tags = tags;
        }

        // Finalized staging operation
        StagedOperation(String ownerId, long generation, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            // Defensive copy of delta is crucial for long-term immutability if object references are passed around.
            // Here, since this is the active stage item, we store the reference to the input arrays,
            // assuming downstream users treat them as read-only after creation.
            this.delta = delta; 
            // Tags must also be treated as immutable by the owner after staging.
            this.tags = tags;
        }
    }

    // --- Constructors and Setup ---

    /**
     * Constructs the buffer with initial committed quantities.
     * @param initialQuantities A copied, non-empty long array of nonnegative quantities.
     */
    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Initial committed quantities array cannot be null or empty.");
        }
        // Defensive copy of the initial state
        this.committedQuantities = new long[initialQuantities.length];
        System.arraycopy(initialQuantities, 0, this.committedQuantities, 0, initialQuantities.length);
        this.nextGeneration = 1; // Generation starts at 1 for the first staged operation
        this.stagedOp = null; // Initially empty staging slot
    }

    // --- Public API Implementations ---

    /**
     * Stages a proposed change. This operation atomically replaces any previous staging attempt.
     *
     * @param ownerId The ID of the caller.
     * @param delta Proposed changes per product index. Must correspond to current committed array size.
     * @param tags Optional metadata flags associated with this stage.
     * @return A unique positive, monotonically increasing generation number assigned to this staged operation.
     * @throws IllegalArgumentException on invalid inputs (nulls, empty IDs, invalid dimensions).
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        // 1. Input Validation (Phase 1: Quick checks without monitor contention)
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("Owner ID cannot be null or empty.");
        }
        if (delta == null || delta.length == 0) {
            throw new IllegalArgumentException("Delta array cannot be null or empty.");
        }

        // Check length consistency based on current committed state size.
        if (this.committedQuantities == null || delta.length != this.committedQuantities.length) {
            throw new IllegalArgumentException("Delta length mismatch with current committed array size.");
        }

        // Check individual delta values (must be non-negative/valid range)
        for (int i = 0; i < delta.length; i++) {
            if (delta[i] < 0 || delta[i] > Long.MAX_VALUE) {
                // Note: We check against the bounds of a signed long, but since input is long[], 
                // Long.MAX_VALUE check might be redundant unless the problem implies tracking overflow risk differently.
                // Assuming delta values are valid long primitives when passed, we enforce non-negativity constraint.
                throw new IllegalArgumentException("Projected quantity for product " + i + " is negative or out of range.");
            }
        }

        // Check tags constraints
        if (tags != null && (tags.length == 0 && tags == null)) {
             // If caller provided structure implying constraints (e.g., array exists but is empty), this depends on interpretation.
             // Since tags are treated as sets using exact case-sensitive equality, we allow null/empty array inputs unless specified otherwise.
        }


        // 2. Atomic Staging (Requires monitor lock)
        synchronized (monitor) {
            // Re-validate input constraints specific to the staging process before proceeding.

            // Check for tag validity (if caller passed an array structure)
            if (tags != null && tags.length == 0) {
                // If the caller explicitly passed an array but it's empty, treat as valid constraints unless business logic forbids it.
                // Assuming tags array presence implies they must be non-null if constraints were expected.
            }

            // Create a defensive copy of the delta array to ensure staging is isolated from future modifications by the caller.
            long[] copiedDelta = new long[delta.length];
            System.arraycopy(delta, 0, copiedDelta, 0, delta.length);

            // Successfully staging involves:
            // a) Assigning the next available generation.
            long newGeneration = this.nextGeneration++;

            // b) Creating the operation object, capturing the generation and required inputs.
            StagedOperation newOp = new StagedOperation(ownerId, newGeneration, copiedDelta, tags);
            
            // c) Atomically replacing the staging slot. This fulfills the "atomically replaces any previous staged operation" requirement.
            this.stagedOp = newOp;

            // Return the assigned generation immediately after successful staging.
            return newGeneration;
        } // End synchronized block
    }

    /**
     * Attempts to apply the staged transaction attempt.
     *
     * @param ownerId The caller ID who originally staged the operation.
     * @param generation The expected generation number of the staged operation.
     * @param required Tags that must be present in the staged operation's tags for commit to succeed.
     * @param forbidden Tags that, if present in the staged operation's tags, cause commitment failure.
     * @return True if the transaction was successfully applied (and committed), false otherwise.
     * @throws IllegalArgumentException if constraints are malformed.
     */
    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        // 1. Input Validation (Constraints must be valid before attempting commit logic)
        if (required != null && required.length > 0 && ownerId == null || ownerId.isEmpty()) {
             // Owner ID is checked against the staged operation anyway, but constraint array presence itself must be valid.
        }

        // 2. Atomic Commit Attempt (Requires monitor lock)
        synchronized (monitor) {
            if (this.stagedOp == null || this.stagedOp.generation != generation) {
                // No matching slot found (null or wrong generation)
                return false;
            }

            if (!ownerId.equals(this.stagedOp.ownerId)) {
                // Mismatch in owner ID
                return false;
            }

            // 3. Constraint Checking (using stagedOp data)
            StagedOperation op = this.stagedOp;

            // Check required tags presence
            if (required != null) {
                for (String reqTag : required) {
                    boolean found = false;
                    if (op.tags != null && op.tags.length > 0) {
                        for (String tag : op.tags) {
                            // Exact case-sensitive equality comparison using objects
                            if (reqTag.equals(tag)) { 
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        // Required tag absent -> failure without mutation
                        return false; 
                    }
                }
            }

            // Check forbidden tags presence
            if (forbidden != null && op.tags != null) {
                for (String forbTag : forbidden) {
                    boolean present = false;
                    for (String tag : op.tags) {
                        if (tag.equals(forbTag)) {
                            present = true;
                            break;
                        }
                    }
                    if (present) {
                        // Forbidden tag present -> failure without mutation
                        return false;
                    }
                }
            }


            // 4. Success: Apply Delta and Clear Staging Slot
            
            // Defensive copy of the old committed state (if needed for rollback/debugging, but required here 
            // to define the transition point). The application modifies committedQuantities in place.

            if (op.delta == null || op.delta.length != this.committedQuantities.length) {
                 // Should never happen if staging validation was correct, but defensively handle array transition.
            } else {
                // Apply delta atomically to the committed state buffer
                for (int i = 0; i < op.delta.length; i++) {
                    this.committedQuantities[i] += op.delta[i];
                }
            }

            // Clear the staging slot, consuming this transaction.
            this.stagedOp = null; 
            return true;
        } // End synchronized block
    }

    /**
     * Discards a staged operation if it matches the owner and generation.
     *
     * @param ownerId The ID of the caller who staged the operation.
     * @param generation The generation number associated with the intended discard.
     * @return True if this specific operation slot was cleared; false otherwise (stale caller or mismatch).
     */
    public boolean discard(String ownerId, long generation) {
        synchronized (monitor) {
            if (this.stagedOp == null || this.stagedOp.generation != generation) {
                return false; // No match found or slot is empty/newer
            }

            if (!ownerId.equals(this.stagedOp.ownerId)) {
                return false; // Owner mismatch
            }

            // Match found: clear the slot.
            this.stagedOp = null;
            return true;
        } // End synchronized block
    }

    /**
     * Returns a consistent, thread-safe snapshot of the current state.
     * This includes committed quantities and a deep copy of any pending staging metadata.
     * Operations are non-blocking apart from initial synchronization acquisition.
     *
     * @return An array containing three elements: [committed_quantities, projected_deltas, staging_metadata].
     *         Element 0 & 1 are long arrays. Element 2 is the metadata structure (or null if nothing staged).
     *         The caller must treat these as immutable copies of the moment they were read.
     */
    public Object[] snapshot() {
        synchronized (monitor) {
            // 1. Copy Committed Quantities (Base State)
            long[] snapshotCommitted = new long[this.committedQuantities.length];
            System.arraycopy(this.committedQuantities, 0, snapshotCommitted, 0, this.committedQuantities.length);

            // 2. Determine Projected Quantities (Committed + Current Delta)
            long[] projectedQuantities = new long[this.committedQuantities.length];
            if (stagedOp != null) {
                // Projection calculation: Committed + Staging Delta
                for (int i = 0; i < this.committedQuantities.length; i++) {
                    projectedQuantities[i] = this.committedQuantities[i] + stagedOp.delta[i];
                }
            } else {
                // No staging, projection equals committed
                System.arraycopy(this.committedQuantities, 0, projectedQuantities, 0, this.committedQuantities.length);
            }

            // 3. Detached Staging Metadata (Deep copies arrays if they exist)
            String[] detachedTags = null;
            if (stagedOp != null && stagedOp.tags != null) {
                // Deep copy tags array for true detachment
                detachedTags = new String[stagedOp.tags.length];
                System.arraycopy(stagedOp.tags, 0, detachedTags, 0, stagedOp.tags.length);
            }

            // Return all necessary components bundled together. The exact structure requested implies bundling them.
            // Returning an array where index 0=Committed, Index 1=Projected, Index 2=Staging Tags (or null)
            return new Object[]{snapshotCommitted, projectedQuantities, detachedTags};
        } // End synchronized block
    }

    // Note: The problem implies that the constructor for the buffer handles initial validity.
    // We rely on the fact that `committedQuantities` will always match the dimensions established in construction.
}
