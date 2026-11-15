@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private long lastGeneration;
    private StagedOperation staged;

    public InventoryTransactionBuffer(long[] committedQuantities) {
        if (committedQuantities == null || committedQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Committed quantities must be non-null and nonempty");
        }

        this.committed = new long[committedQuantities.length];
        for (int i = 0; i < committedQuantities.length; i++) {
            long quantity = committedQuantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException(
                        "Committed quantities must be nonnegative");
            }
            this.committed[i] = quantity;
        }
        this.lastGeneration = 0L;
        this.staged = null;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwnerId(ownerId);
        if (delta == null || tags == null) {
            throw new IllegalArgumentException("Delta and tags must not be null");
        }

        long[] copiedDelta = new long[delta.length];
        for (int i = 0; i < delta.length; i++) {
            copiedDelta[i] = delta[i];
        }
        String[] copiedTags = copyAndDeduplicateTags(tags);

        synchronized (this) {
            if (copiedDelta.length != committed.length) {
                throw new IllegalArgumentException(
                        "Delta must have one quantity per product");
            }
            validateProjectedQuantities(committed, copiedDelta);
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = lastGeneration + 1L;
            StagedOperation replacement =
                    new StagedOperation(ownerId, generation, copiedDelta, copiedTags);
            lastGeneration = generation;
            staged = replacement;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation,
                          String[] required, String[] forbidden) {
        validateOwnerId(ownerId);
        validateGeneration(generation);
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException(
                    "Required and forbidden tags must not be null");
        }

        String[] copiedRequired = copyAndDeduplicateTags(required);
        String[] copiedForbidden = copyAndDeduplicateTags(forbidden);

        synchronized (this) {
            if (staged == null
                    || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }

            for (int i = 0; i < copiedRequired.length; i++) {
                if (!contains(staged.tags, copiedRequired[i])) {
                    return false;
                }
            }
            for (int i = 0; i < copiedForbidden.length; i++) {
                if (contains(staged.tags, copiedForbidden[i])) {
                    return false;
                }
            }

            for (int i = 0; i < committed.length; i++) {
                committed[i] += staged.delta[i];
            }
            staged = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwnerId(ownerId);
        validateGeneration(generation);

        synchronized (this) {
            if (staged == null
                    || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }
            staged = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            long[] committedCopy = copyLongArray(committed);
            long[] projected = copyLongArray(committed);

            String ownerId = null;
            long generation = 0L;
            long[] delta = null;
            String[] tags = null;

            if (staged != null) {
                ownerId = staged.ownerId;
                generation = staged.generation;
                delta = copyLongArray(staged.delta);
                tags = copyStringArray(staged.tags);
                for (int i = 0; i < projected.length; i++) {
                    projected[i] += staged.delta[i];
                }
            }

            return new Snapshot(
                    committedCopy, projected, ownerId, generation, delta, tags);
        }
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must not be null or empty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyAndDeduplicateTags(String[] tags) {
        String[] copied = new String[tags.length];
        int count = 0;

        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i];
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException("Tags must not be null or empty");
            }
            if (!contains(copied, count, tag)) {
                copied[count++] = tag;
            }
        }

        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = copied[i];
        }
        return result;
    }

    private static boolean contains(String[] values, String value) {
        return contains(values, values.length, value);
    }

    private static boolean contains(String[] values, int length, String value) {
        for (int i = 0; i < length; i++) {
            if (values[i].equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void validateProjectedQuantities(long[] quantities, long[] delta) {
        for (int i = 0; i < quantities.length; i++) {
            long current = quantities[i];
            long change = delta[i];

            if (change >= 0L) {
                if (current > Long.MAX_VALUE - change) {
                    throw new IllegalArgumentException(
                            "Projected quantity exceeds Long.MAX_VALUE");
                }
            } else {
                if (change == Long.MIN_VALUE || current < -change) {
                    throw new IllegalArgumentException(
                            "Projected quantity would be negative");
                }
            }
        }
    }

    private static long[] copyLongArray(long[] source) {
        long[] copy = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] copyStringArray(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static final class StagedOperation {
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
    }

    public static final class Snapshot {
        public final long[] committedQuantities;
        public final long[] projectedQuantities;
        public final String stagingOwnerId;
        public final long stagingGeneration;
        public final long[] stagingDelta;
        public final String[] stagingTags;

        private Snapshot(long[] committedQuantities, long[] projectedQuantities,
                         String stagingOwnerId, long stagingGeneration,
                         long[] stagingDelta, String[] stagingTags) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.stagingOwnerId = stagingOwnerId;
            this.stagingGeneration = stagingGeneration;
            this.stagingDelta = stagingDelta;
            this.stagingTags = stagingTags;
        }

        public boolean hasStagedOperation() {
            return stagingGeneration != 0L;
        }

        public long[] getCommittedQuantities() {
            return copyLongArray(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copyLongArray(projectedQuantities);
        }

        public long[] getStagingDelta() {
            return stagingDelta == null ? null : copyLongArray(stagingDelta);
        }

        public String[] getStagingTags() {
            return stagingTags == null ? null : copyStringArray(stagingTags);
        }
    }
}
