@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration;
    private StagingMetadata staged;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Initial quantities must be nonnull and nonempty.");
        }

        long[] copy = copyLongs(initialQuantities);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException(
                        "Committed quantities must be nonnegative.");
            }
        }
        committed = copy;
    }

    /**
     * Replaces the staging slot without waiting for it to become empty.
     * Throws IllegalStateException if all positive generations have been used.
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException(
                    "Delta must contain one quantity per product.");
        }

        long[] deltaCopy = copyLongs(delta);
        String[] tagCopy = copyTagSet(tags, false);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                long quantity = committed[i];
                long change = deltaCopy[i];
                if (change < -quantity
                        || change > Long.MAX_VALUE - quantity) {
                    throw new IllegalArgumentException(
                            "Projected quantity is outside the valid range.");
                }
            }

            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted.");
            }

            long generation = lastGeneration + 1;
            StagingMetadata replacement = new StagingMetadata(
                    ownerId, generation, deltaCopy, tagCopy);

            staged = replacement;
            lastGeneration = generation;
            return generation;
        }
    }

    /**
     * Invalid constraints throw even when the owner or generation is stale.
     * A missing slot or an unmet constraint returns false without waiting.
     */
    public boolean commit(
            String ownerId,
            long generation,
            String[] required,
            String[] forbidden) {

        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyTagSet(required, true);
        String[] forbiddenCopy = copyTagSet(forbidden, true);

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
                return false;
            }

            for (int i = 0; i < requiredCopy.length; i++) {
                if (!contains(staged.tags, requiredCopy[i])) {
                    return false;
                }
            }

            for (int i = 0; i < forbiddenCopy.length; i++) {
                if (contains(staged.tags, forbiddenCopy[i])) {
                    return false;
                }
            }

            // Committed quantities cannot change while this slot survives.
            // Its delta was range-checked when the slot was installed.
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

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
                return false;
            }
            staged = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = copyLongs(committed);
            long[] projectedCopy = copyLongs(committed);
            StagingMetadata metadata = null;

            if (staged != null) {
                for (int i = 0; i < projectedCopy.length; i++) {
                    projectedCopy[i] += staged.delta[i];
                }

                metadata = new StagingMetadata(
                        staged.ownerId,
                        staged.generation,
                        copyLongs(staged.delta),
                        copyStrings(staged.tags));
            }

            return new Snapshot(committedCopy, projectedCopy, metadata);
        }
    }

    // Called only while holding lock.
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
            throw new IllegalArgumentException(
                    "Generation must be positive.");
        }
    }

    private static String[] copyTagSet(
            String[] input, boolean allowEmpty) {
        if (input == null) {
            throw new IllegalArgumentException("Tag array must be nonnull.");
        }
        if (!allowEmpty && input.length == 0) {
            throw new IllegalArgumentException(
                    "Staged tags must be nonempty.");
        }

        String[] unique = new String[input.length];
        int count = 0;

        for (int i = 0; i < input.length; i++) {
            String tag = input[i];
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(
                        "Tags must be nonnull and nonempty.");
            }

            boolean duplicate = false;
            for (int j = 0; j < count; j++) {
                if (unique[j].equals(tag)) {
                    duplicate = true;
                    break;
                }
            }

            if (!duplicate) {
                unique[count++] = tag;
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

    private static boolean contains(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static long[] copyLongs(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copyStrings(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final StagingMetadata stagingMetadata;

        private Snapshot(
                long[] committedQuantities,
                long[] projectedQuantities,
                StagingMetadata stagingMetadata) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.stagingMetadata = stagingMetadata;
        }

        public long[] getCommittedQuantities() {
            return copyLongs(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copyLongs(projectedQuantities);
        }

        /** Returns null when the staging slot was empty. */
        public StagingMetadata getStagingMetadata() {
            return stagingMetadata;
        }
    }

    public static final class StagingMetadata {
        private final String ownerId;
        private final long generation;
        private final long[] delta;
        private final String[] tags;

        private StagingMetadata(
                String ownerId,
                long generation,
                long[] delta,
                String[] tags) {
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
            return copyLongs(delta);
        }

        public String[] getTags() {
            return copyStrings(tags);
        }
    }
}
