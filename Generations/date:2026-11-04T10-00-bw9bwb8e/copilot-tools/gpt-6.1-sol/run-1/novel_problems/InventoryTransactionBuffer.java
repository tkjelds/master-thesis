@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private static final long MAX_QUANTITY = 0x7fffffffffffffffL;

    private final Object monitor = new Object();
    private final long[] committed;
    private StagedOperation staged;
    private long lastGeneration;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Initial quantities must be nonnull and nonempty.");
        }

        long[] copy = copy(initialQuantities);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException(
                        "Committed quantities must be nonnegative.");
            }
        }
        committed = copy;
    }

    /**
     * Immediately replaces the shared slot; never waits for it to become empty.
     * Tags must contain at least one nonnull, nonempty string.
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException(
                    "Delta must contain one quantity per product.");
        }

        long[] deltaCopy = copy(delta);
        String[] tagSet = copyTagSet(tags, false);

        synchronized (monitor) {
            for (int i = 0; i < committed.length; i++) {
                checkedAdd(committed[i], deltaCopy[i]);
            }

            if (lastGeneration == MAX_QUANTITY) {
                throw new IllegalStateException("Generation counter exhausted.");
            }

            long generation = lastGeneration + 1;
            StagedOperation replacement =
                    new StagedOperation(ownerId, generation, deltaCopy, tagSet);
            staged = replacement;
            lastGeneration = generation;
            return generation;
        }
    }

    /**
     * Returns immediately, including when the slot is empty or does not match.
     * Invalid constraints throw even when no matching slot exists.
     */
    public boolean commit(String ownerId, long generation,
                          String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredSet = copyTagSet(required, true);
        String[] forbiddenSet = copyTagSet(forbidden, true);

        synchronized (monitor) {
            if (!matches(ownerId, generation)) {
                return false;
            }

            for (int i = 0; i < requiredSet.length; i++) {
                if (!contains(staged.tags, requiredSet[i])) {
                    return false;
                }
            }

            for (int i = 0; i < forbiddenSet.length; i++) {
                if (contains(staged.tags, forbiddenSet[i])) {
                    return false;
                }
            }

            // Committed quantities cannot change while this slot survives.
            for (int i = 0; i < committed.length; i++) {
                committed[i] += staged.delta[i];
            }
            staged = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwner(ownerId);
        validateGeneration(generation);

        synchronized (monitor) {
            if (!matches(ownerId, generation)) {
                return false;
            }
            staged = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            long[] committedCopy = copy(committed);
            long[] projectedCopy = copy(committed);
            StagedOperation stagedCopy = null;

            if (staged != null) {
                for (int i = 0; i < projectedCopy.length; i++) {
                    projectedCopy[i] += staged.delta[i];
                }
                stagedCopy = new StagedOperation(
                        staged.ownerId,
                        staged.generation,
                        copy(staged.delta),
                        copy(staged.tags));
            }

            return new Snapshot(committedCopy, projectedCopy, stagedCopy);
        }
    }

    // Caller holds monitor.
    private boolean matches(String ownerId, long generation) {
        return staged != null
                && staged.generation == generation
                && staged.ownerId.equals(ownerId);
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException(
                    "Owner ID must be nonnull and nonempty.");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive.");
        }
    }

    private static long checkedAdd(long quantity, long delta) {
        if (delta < -quantity
                || (delta > 0 && quantity > MAX_QUANTITY - delta)) {
            throw new IllegalArgumentException(
                    "Projected quantity is outside the permitted range.");
        }
        return quantity + delta;
    }

    private static String[] copyTagSet(String[] tags, boolean allowEmpty) {
        if (tags == null) {
            throw new IllegalArgumentException("Tag arrays must be nonnull.");
        }
        if (!allowEmpty && tags.length == 0) {
            throw new IllegalArgumentException(
                    "Staged tags must contain at least one tag.");
        }

        String[] input = copy(tags);
        for (int i = 0; i < input.length; i++) {
            if (input[i] == null || input[i].length() == 0) {
                throw new IllegalArgumentException(
                        "Tags must be nonnull and nonempty.");
            }
        }

        String[] unique = new String[input.length];
        int count = 0;
        for (int i = 0; i < input.length; i++) {
            boolean duplicate = false;
            for (int j = 0; j < count; j++) {
                if (unique[j].equals(input[i])) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique[count++] = input[i];
            }
        }

        if (count == unique.length) {
            return unique;
        }

        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = unique[i];
        }
        return result;
    }

    private static boolean contains(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static long[] copy(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copy(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final StagedOperation stagedOperation;

        private Snapshot(long[] committedQuantities,
                         long[] projectedQuantities,
                         StagedOperation stagedOperation) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.stagedOperation = stagedOperation;
        }

        public long[] getCommittedQuantities() {
            return copy(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copy(projectedQuantities);
        }

        /** Returns immutable, detached metadata, or null if the slot was empty. */
        public StagedOperation getStagedOperation() {
            return stagedOperation;
        }
    }

    public static final class StagedOperation {
        private final String ownerId;
        private final long generation;
        private final long[] delta;
        private final String[] tags;

        private StagedOperation(String ownerId, long generation,
                                long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public long getGeneration() {
            return generation;
        }

        public long[] getDelta() {
            return copy(delta);
        }

        public String[] getTags() {
            return copy(tags);
        }
    }
}
