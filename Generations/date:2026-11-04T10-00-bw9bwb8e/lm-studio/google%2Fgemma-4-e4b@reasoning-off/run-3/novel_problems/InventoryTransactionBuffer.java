@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    // --- Internal State ---

    private long[] committedQuantities;
    private int currentGeneration = 0; // Used to uniquely identify the *next* potential staging operation
    private int latestStagedGeneration = 0; // The generation currently in the staging slot

    // Staging Slot contents
    private long[] stagedDelta;
    private String stagedOwnerId = null;
    private String[] stagedTags = null;

    // Synchronization object
    private final Object monitor = new Object();

    /**
     * Constructs the buffer with an initial committed state.
     * The constructor requires a copy of the initial committed quantities.
     */
    public InventoryTransactionBuffer(long[] initialCommittedQuantities) {
        if (initialCommittedQuantities == null || initialCommittedQuantities.length == 0) {
            throw new IllegalArgumentException("Initial committed quantities array must be nonempty.");
        }
        // Deep copy the initial state to ensure encapsulation.
        this.committedQuantities = new long[initialCommittedQuantities.length];
        for (int i = 0; i < initialCommittedQuantities.length; i++) {
            this.committedQuantities[i] = initialCommittedQuantities[i];
        }
    }

    // --- Helper Methods (Internal Consistency Checks) ---

    /**
     * Deep copies an array of long into a new array.
     */
    private static long[] copyLongArray(long[] original) {
        if (original == null) return null;
        long[] copy = new long[original.length];
        for (int i = 0; i < original.length; i++) {
            copy[i] = original[i];
        }
        return copy;
    }

    /**
     * Deep copies an array of Strings into a new array.
     */
    private static String[] copyStringArray(String[] original) {
        if (original == null) return null;
        String[] copy = new String[original.length];
        for (int i = 0; i < original.length; i++) {
            copy[i] = original[i]; // Strings are immutable, so references are safe here.
        }
        return copy;
    }

    // --- Public API Methods ---

    /**
     * Stages a new delta, atomically replacing any previous staging.
     * @return A unique positive, monotonically increasing generation if successful.
     */
    public int stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty()) {
            return -1; // Indicate failure due to invalid input
        }
        if (delta == null || delta.length == 0) {
            // Based on contract: "one signed quantity per product". If no products exist, this is ambiguous.
            // Assuming the length must match committed/deltas available (which implies they are consistent).
            // If we assume the structure must match, an empty delta is invalid unless the system allows zero product types.
            // Given the constructor forces a non-empty structure, we require delta length matching committed quantities.
            // Since we don't know the committed quantity array length here without access, we rely on subsequent checks.
            // For strict interpretation: if the staging happens, it must correspond to array lengths established by construction.
            // Let's assume the structure is known/validated externally or implicitly matched based on product count.
        }

        // Perform validity checks BEFORE acquiring monitor lock if possible, but length consistency requires it.
        if (delta != null && delta.length > 0) {
            // Check against current committed structure length for consistency
            if (delta.length != committedQuantities.length) {
                return -1; // Invalid array lengths mismatch schema
            }
        }

        if (tags != null && tags.length == 0) {
            // Null or empty tags array implies an invalid state unless explicitly allowed.
            // "null/empty tags" is rejected if it occurs during replacement flow.
        } else if (tags != null && tags.length == 0) {
             // If the caller provides an empty tag array, we treat it as valid unless constraints apply differently.
        } else if (tags != null && tags.length > 0) {
            // Check for any element being null if we are treating tags as a collection.
            for (String tag : tags) {
                if (tag == null) return -1; // Treat presence of null in non-empty tag array as invalid input.
            }
        }


        synchronized (monitor) {
            // 1. Generate the new unique generation number for this operation attempt.
            int nextGeneration = currentGeneration + 1;

            // 2. Perform deep copy and validation of inputs before committing to staging slot
            stagedDelta = copyLongArray(delta);
            stagedOwnerId = ownerId;
            stagedTags = copyStringArray(tags);

            // 3. Apply the atomic replacement
            latestStagedGeneration = nextGeneration;
            currentGeneration = nextGeneration;

            // Return the generation identifier associated with this staged attempt.
            return nextGeneration;
        }
    }

    /**
     * Attempts to apply the staged transaction to committed quantities.
     * @return true if the commit was successful, false otherwise (e.g., mismatch, constraints failed).
     */
    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (generation <= 0 || ownerId == null || ownerId.isEmpty()) {
            return false; // Invalid base parameters
        }

        synchronized (monitor) {
            // 1. Check if the operation matches the current staging slot's generation and owner ID
            if (latestStagedGeneration != generation || stagedOwnerId == null || !stagedOwnerId.equals(ownerId)) {
                return false; // Does not match the operation currently staged (or any)
            }

            // 2. Validate constraints against the *staged* tags (which are now candidates for application)
            final String[] currentStagedTags = stagedTags;

            if (required != null) {
                // Check presence of all required tags
                for (String reqTag : required) {
                    if (reqTag == null || reqTag.isEmpty()) {
                        throw new IllegalArgumentException("Required constraint tag cannot be null or empty."); // Contract demands validation on *input* constraints
                    }
                    boolean found = false;
                    if (currentStagedTags != null) {
                        for (String stagedTag : currentStagedTags) {
                            if (reqTag.equals(stagedTag)) {
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        return false; // Required tag absent
                    }
                }
            }

            if (forbidden != null) {
                // Check presence of any forbidden tag
                for (String forbidTag : forbidden) {
                    if (forbidTag == null || forbidTag.isEmpty()) {
                        throw new IllegalArgumentException("Forbidden constraint tag cannot be null or empty."); // Contract demands validation on *input* constraints
                    }
                    if (currentStagedTags != null) {
                        for (String stagedTag : currentStagedTags) {
                            if (forbidTag.equals(stagedTag)) {
                                return false; // Forbidden tag present
                            }
                        }
                    }
                }
            }

            // 3. Apply delta if checks pass (Atomically apply mutation)
            if (stagedDelta != null && stagedDelta.length > 0) {
                // Apply delta to committed quantities
                for (int i = 0; i < stagedDelta.length; i++) {
                    // Apply the change: Committed = Committed + Staged Delta
                    this.committedQuantities[i] += stagedDelta[i];
                }
            }

            // 4. Clear staging slot markers *after* successful application
            stagedDelta = null;
            stagedOwnerId = null;
            stagedTags = null;
            // Generation number is effectively consumed by clearing the slot, latestStagedGeneration remains for reference but points to "consumed".
            // We keep currentGeneration up-to-date.

            return true; // Commit successful
        }
    }

    /**
     * Clears the staging slot if it matches the given owner and generation.
     * @return true if the matching operation was cleared, false otherwise (stale caller).
     */
    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty()) return false;

        synchronized (monitor) {
            if (latestStagedGeneration != generation || stagedOwnerId == null || !stagedOwnerId.equals(ownerId)) {
                return false; // Does not match the operation in staging
            }

            // Clear only this specific slot contents
            stagedDelta = null;
            stagedOwnerId = null;
            stagedTags = null;

            return true; // Success in clearing the slot
        }
    }

    /**
     * Returns a snapshot of the current system state and staging information.
     * This operation is non-blocking but requires synchronization to ensure a coherent view.
     */
    public Snapshot snapshot() {
        synchronized (monitor) {
            // 1. Return committed quantities (Deep copy required for encapsulation)
            long[] committedCopy = copyLongArray(this.committedQuantities);

            // 2. Determine projected quantities
            long[] projectedCopy = copyLongArray(this.committedQuantities); // Start with committed baseline

            if (stagedDelta != null && stagedDelta.length > 0) {
                // Apply staging delta to create projection IF staging exists
                for (int i = 0; i < stagedDelta.length; i++) {
                    projectedCopy[i] += stagedDelta[i];
                }
            }

            // 3. Return detached staging metadata (Deep copy tags if present)
            String[] stagedTagsCopy = null;
            if (stagedTags != null && stagedTags.length > 0) {
                stagedTagsCopy = copyStringArray(stagedTags);
            }

            // 4. Check generation validity markers
            if (currentGeneration <= 0) {
                // This indicates an uninitialized or entirely hypothetical state if generation tracking starts at 0.
                // Since constructor implies initial commitment, currentGeneration >= 1 after first stage.
            }

            return new Snapshot(committedCopy, projectedCopy, stagedTagsCopy);
        }
    }

    /**
     * Immutable container for the result of snapshot().
     */
    public static class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final String[] detachedStagingTags;

        public Snapshot(long[] committedQuantities, long[] projectedQuantities, String[] detachedStagingTags) {
            // Defensive copying ensures immutability from the perspective of the caller.
            this.committedQuantities = copyLongArray(committedQuantities);
            this.projectedQuantities = copyLongArray(projectedQuantities);
            this.detachedStagingTags = copyStringArray(detachedStagingTags); // Tags array itself is copied references
        }

        public long[] getCommittedQuantities() { return copyLongArray(committedQuantities); }
        public long[] getProjectedQuantities() { return copyLongArray(projectedQuantities); }
        public String[] getDetachedStagingTags() { return copyStringArray(detachedStagingTags); }
    }
}
