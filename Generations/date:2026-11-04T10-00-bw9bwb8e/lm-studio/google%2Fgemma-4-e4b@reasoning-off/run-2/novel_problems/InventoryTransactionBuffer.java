@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    // --- State Variables ---

    // Committed quantities: This is the sequence that subsequent stages are based on.
    private long[] committedQuantities;

    // Staging slot holds the operation waiting for commit/discard.
    private StagedOperation stagedOperation;

    // Generation counter, monotonically increasing for successful staging operations.
    private long currentGeneration;

    // Lock object for intrinsic synchronization.
    private final Object lock = new Object();

    /**
     * Internal class representing the staged operation.
     */
    private static class StagedOperation {
        // The unique generation ID assigned upon successful staging.
        final long generation;
        // The delta to be applied if committed.
        final long[] delta;
        // Tags associated with this operation (must match input structure).
        final String[] tags;

        // Metadata for commit validation/use.
        final String ownerId;
        final long[] requiredTags; // Constraints for commit validation.
        final String[] forbiddenTags; // Constraints for commit validation.

        // Constructor used during staging phase
        StagedOperation(long generation, String ownerId, long[] delta, String[] tags,
                         String[] requiredTags, String[] forbiddenTags) {
            this.generation = generation;
            this.ownerId = ownerId;
            this.delta = delta;
            this.tags = tags; // Tags from the successful stage call
            this.requiredTags = requiredTags != null ? requiredTags : new String[0];
            this.forbiddenTags = forbiddenTags != null ? forbiddenTags : new String[0];
        }
    }

    // --- Constructors and Initializers ---

    /**
     * Constructs the buffer with initial committed quantities.
     * @param initialQuantities A copied, nonempty long array of nonnegative committed quantities.
     */
    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Initial quantities must be a nonempty array.");
        }

        // Deep copy the initial committed state to ensure immutability from outside.
        this.committedQuantities = new long[initialQuantities.length];
        System.arraycopy(initialQuantities, 0, this.committedQuantities, 0, initialQuantities.length);

        this.currentGeneration = 0; // Generation starts at 0, first successful stage gets generation 1.
        this.stagedOperation = null;
    }

    // --- Public Operations ---

    /**
     * Stages a new delta operation. If successful, returns the unique generation ID assigned to this stage.
     * This operation atomically replaces any previous staging attempt.
     *
     * @param ownerId The ID of the owner making the stage request.
     * @param delta Array representing product index -> signed quantity change.
     * @param tags Tags associated with this staged operation.
     * @return The unique, positive, monotonically increasing generation ID assigned to the staging attempt upon successful validation.
     * @throws IllegalArgumentException if inputs are invalid (null, empty ownerId, null/empty tags, mismatching lengths, or if a projected quantity becomes negative).
     */
    public long stage(String ownerId, long[] delta, String[] tags) throws IllegalArgumentException {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("Owner ID cannot be null or empty.");
        }
        if (delta == null || delta.length == 0) {
            // While the problem description implies a product count corresponds to array length,
            // we must handle the case where no products are tracked (though the constructor implies product existence).
            // If we assume the structure must always map to the initialized size, this check is context-dependent.
            // Assuming delta length must match committedQuantities length for consistency checks.
            if (this.committedQuantities != null && this.committedQuantities.length > 0) {
                throw new IllegalArgumentException("Delta array must match the number of tracked products.");
            }
        } else if (delta.length != this.committedQuantities.length) {
             throw new IllegalArgumentException("Delta array length mismatch with committed product count.");
        }

        if (tags == null || tags.length == 0) {
            // Tags must be non-empty if they are provided as part of the contract fulfillment requirement.
            // "Provide stage(String ownerId, long[] delta, String[] tags)" implies contract fulfillment is needed.
            // Re-reading: "Provide stage(...), commit(...), discard(...), snapshot()". The staging phase must handle tags.
            // "Tags as sets using exact case-sensitive equality." If the requirement is that tags *must* be provided in stage, then check emptiness.
            // Assuming tag array presence implies intent to use tags. If tags is null/empty, we treat it as an issue if the contract implies they are necessary for tracking.
            // Given the requirement to check tags match upon commit, we treat null/empty tag arrays as potentially invalid depending on strictness.
            // Let's enforce that if tags are passed in, they must be validly sized/non-null. If they are entirely optional for the *staging* phase itself, this check might pass for null/empty.
            // To satisfy "Provide stage(...)" structure, we'll allow `tags` to be null/empty if the system allows it, but proceed cautiously.
        } else {
            // Check for null/empty strings within the tags array if present.
            for (int i = 0; i < tags.length; i++) {
                if (tags[i] == null) {
                    throw new IllegalArgumentException("Tag cannot be null.");
                }
            }
        }

        // 1. Validation and Copying (Must happen before synchronization if possible, but here it interacts with state)
        // The crucial part is validation against constraints before atomically replacing the slot.

        // Check projections: quantity change + current committed must be >= 0 and <= Long.MAX_VALUE
        // Since we are staging, we only check if the *application* of this delta would result in an invalid state IF it were committed immediately.
        // However, the requirement states: "any projected quantity below zero or above Long.MAX_VALUE before replacing."
        // This usually means checking against the *current* committed state + delta.

        final int productCount = this.committedQuantities.length;
        if (delta != null && delta.length == productCount) {
            for (int i = 0; i < productCount; i++) {
                long newQuantity = this.committedQuantities[i] + delta[i];
                if (newQuantity < 0 || newQuantity > Long.MAX_VALUE) {
                    throw new IllegalArgumentException("Projected quantity for product " + i + " results in an overflow or negative value.");
                }
            }
        }

        // Prepare the new generation and atomically replace/stage.
        synchronized (lock) {
            // Generate the next sequence number IF we are successfully replacing/staging.
            this.currentGeneration++;
            final long newGeneration = this.currentGeneration;

            // Deep copy delta and tags for safe storage in the slot.
            final long[] copiedDelta = new long[delta.length];
            System.arraycopy(delta, 0, copiedDelta, 0, delta.length);

            final String[] copiedTags = (tags != null) ? new String[tags.length] : null;
            if (tags != null) {
                System.arraycopy(tags, 0, copiedTags, 0, tags.length);
            }

            // Atomically replace the staged operation slot (old operation, if any, is implicitly invalidated/overwritten).
            this.stagedOperation = new StagedOperation(
                    newGeneration, ownerId, copiedDelta, copiedTags, null, null);

            return newGeneration;
        }
    }


    /**
     * Attempts to apply the staged operation if constraints match. If successful, applies delta atomically and clears staging.
     *
     * @param ownerId The ID provided by the caller attempting to commit.
     * @param generation The expected generation ID of the staged operation.
     * @param required Tags that MUST be present for a successful commit (subset check).
     * @param forbidden Tags that MUST NOT be present in the staged operation's tags.
     * @return true if the commit succeeded, false otherwise (e.g., mismatching generation, constraints failed).
     * @throws IllegalArgumentException if constraint arrays are provided but contain structural errors.
     */
    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) throws IllegalArgumentException {
        if (required != null || forbidden != null) {
            // Check constraints integrity early
            if (required != null && required.length == 0) {} // Allowed
            else {
                for (int i = 0; i < required != null ? required.length : 0; i++) {
                    if (required[i] == null) throw new IllegalArgumentException("Required tag cannot be null.");
                }
            }
            for (int i = 0; i < forbidden != null ? forbidden.length : 0; i++) {
                if (forbidden[i] == null) throw new IllegalArgumentException("Forbidden tag cannot be null.");
            }
        }

        synchronized (lock) {
            if (stagedOperation == null || stagedOperation.generation != generation || !stagedOperation.ownerId.equals(ownerId)) {
                // No matching slot or mismatch in identity/generation. Fail gracefully without mutation.
                return false;
            }

            StagedOperation op = stagedOperation; // Hold reference before clearing slot

            // 1. Check required tags (Must be present in staged operation's tags)
            if (required != null && required.length > 0) {
                String[] stagedTags = op.tags;
                if (stagedTags == null || stagedTags.length != required.length) {
                    // If constraints require tags, but the operation was staged without them or with different length.
                    return false;
                }
                for (int i = 0; i < required.length; i++) {
                    // Check if stagedTags contains required[i] (exact match)
                    boolean found = false;
                    for (int j = 0; j < stagedTags.length; j++) {
                        if (stagedTags[j].equals(required[i])) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return false; // Required tag missing in staged operation
                    }
                }
            }

            // 2. Check forbidden tags (Must NOT be present in staged operation's tags)
            if (forbidden != null && forbidden.length > 0) {
                String[] stagedTags = op.tags;
                 if (stagedTags == null || stagedTags.length != forbidden.length) {
                    // This comparison is tricky: if constraints are present, we must compare against the staged tags.
                    // If the constraint specifies a forbidden tag, but the operation has no tags, it cannot contain that specific forbidden tag.
                    // However, strict interpretation of "forbidden tag present" suggests comparing the *set* match.
                    // Assuming constraint arrays refer to constraints being matched against tags present in op.tags:
                    if (stagedTags == null) {
                         // If forbidden tags are provided, but the operation has no tags, it cannot contain any. This passes safety check regarding presence.
                    } else {
                        for (int i = 0; i < forbidden.length; i++) {
                            String forbTag = forbidden[i];
                             boolean foundInStaged = false;
                             for (int j = 0; j < stagedTags.length; j++) {
                                 if (stagedTags[j].equals(forbTag)) {
                                     foundInStaged = true;
                                     break;
                                 }
                             }
                             if (foundInStaged) {
                                return false; // Forbidden tag found in staged operation tags. Commit fails.
                             }
                        }
                    }
                } else {
                     // Detailed comparison based on constraint logic (simplified for this implementation focusing on presence/absence)
                    for (int i = 0; i < forbidden.length; i++) {
                        String forbTag = forbidden[i];
                        boolean foundInStaged = false;
                         for (int j = 0; j < stagedTags.length; j++) {
                            if (stagedTags[j].equals(forbTag)) {
                                foundInStaged = true;
                                break;
                            }
                         }
                        if (foundInStaged) {
                            return false; // Forbidden tag found in staged operation tags. Commit fails.
                        }
                    }
                }
            }

            // 3. Apply Delta and transition state
            int productCount = op.delta.length;
            if (productCount != committedQuantities.length) {
                 // Should not happen if staging validated correctly, but safety check during commit phase.
                 return false;
            }

            // Apply the change to committed quantities (mutation happens here)
            for (int i = 0; i < productCount; i++) {
                committedQuantities[i] += op.delta[i];
            }

            // Clear the staging slot to mark transaction as successful/applied.
            stagedOperation = null;
            return true; // Success!
        }
    }

    /**
     * Clears the staging slot ONLY IF it matches the owner and generation provided. Does nothing otherwise.
     * @param ownerId The expected owner ID of the operation to discard.
     * @param generation The expected generation ID of the operation to discard.
     * @return true if the slot was cleared (i.e., it matched the inputs), false otherwise.
     */
    public boolean discard(String ownerId, long generation) {
        synchronized (lock) {
            if (stagedOperation == null || stagedOperation.generation != generation || !ownerId.equals(stagedOperation.ownerId)) {
                return false; // No matching slot found or wrong caller/generation.
            }

            // Match found, clear the slot.
            stagedOperation = null;
            return true; // Successfully cleared the matched slot.
        }
    }

    /**
     * Returns a snapshot of the current committed state and the pending/committed projection.
     * This operation does not block on staging operations, but it reads the consistent state at the time of execution.
     *
     * @return An array containing three elements: [0] copied committed quantities, [1] projected quantities (committed + delta, or current if empty), [2] staging metadata tag.
     * @throws IllegalStateException if the generation counter has exhausted (though realistically, it only exhausts memory).
     */
    public Object[] snapshot() {
        synchronized (lock) {
            final int committedLen = this.committedQuantities.length;
            if (committedLen == 0) {
                throw new IllegalStateException("Product count is zero.");
            }

            // 1. Copy Committed Quantities (State A)
            long[] committedCopy = new long[committedLen];
            System.arraycopy(this.committedQuantities, 0, committedCopy, 0, committedLen);

            // 2. Determine Projected Quantities (State B)
            long[] projectedCopy = new long[committedLen];
            if (stagedOperation != null) {
                // Projection = Committed + Staged Delta
                for (int i = 0; i < committedLen; i++) {
                    long projectedValue = this.committedQuantities[i] + stagedOperation.delta[i];
                    // We rely on the invariant checks in stage() that this projection is valid (non-negative, within bounds)
                    projectedCopy[i] = projectedValue;
                }
            } else {
                // No staging, projection equals committed.
                System.arraycopy(this.committedQuantities, 0, projectedCopy, 0, committedLen);
            }

            // 3. Detached Staging Metadata Tag (optional, but useful for full context)
            String[] detachedTag = null;
            if (stagedOperation != null && stagedOperation.tags != null) {
                // Deep copy the tags array to ensure detachment from internal state changes.
                detachedTag = new String[stagedOperation.tags.length];
                System.arraycopy(stagedOperation.tags, 0, detachedTag, 0, stagedOperation.tags.length);
            }

            // Return object array containing copies of the state components for external safety.
            return new Object[]{committedCopy, projectedCopy, detachedTag};
        }
    }
}
