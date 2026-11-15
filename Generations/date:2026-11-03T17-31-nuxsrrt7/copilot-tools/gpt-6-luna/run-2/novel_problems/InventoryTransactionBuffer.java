@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object mutex = new Object();
    private final long[] committed;
    private long lastGeneration;
    private StagedOperation staged;

    private static final class StagedOperation {
        final String ownerId;
        final long generation;
        final long[] delta;
        final String[] tags;

        StagedOperation(String ownerId, long generation, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final String stagedOwnerId;
        private final long stagedGeneration;
        private final long[] stagedDelta;
        private final String[] stagedTags;

        private Snapshot(long[] committedQuantities, long[] projectedQuantities,
                         StagedOperation staged) {
            this.committedQuantities = copy(committedQuantities);
            this.projectedQuantities = copy(projectedQuantities);
            if (staged == null) {
                this.stagedOwnerId = null;
                this.stagedGeneration = 0;
                this.stagedDelta = null;
                this.stagedTags = null;
            } else {
                this.stagedOwnerId = staged.ownerId;
                this.stagedGeneration = staged.generation;
                this.stagedDelta = copy(staged.delta);
                this.stagedTags = copy(staged.tags);
            }
        }

        public long[] getCommittedQuantities() {
            return copy(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copy(projectedQuantities);
        }

        public boolean hasStagedOperation() {
            return stagedOwnerId != null;
        }

        public String getStagedOwnerId() {
            return stagedOwnerId;
        }

        public long getStagedGeneration() {
            return stagedGeneration;
        }

        public long[] getStagedDelta() {
            return stagedDelta == null ? null : copy(stagedDelta);
        }

        public String[] getStagedTags() {
            return stagedTags == null ? null : copy(stagedTags);
        }
    }

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Initial quantities must be nonempty");
        }
        this.committed = copy(initialQuantities);
        for (long quantity : committed) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Committed quantities must be nonnegative");
            }
        }
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwnerId(ownerId);
        if (delta == null || tags == null) {
            throw new IllegalArgumentException("Delta and tags must not be null");
        }
        long[] deltaCopy = copy(delta);
        String[] tagsCopy = copyAndValidateTags(tags);
        if (deltaCopy.length != committed.length) {
            throw new IllegalArgumentException("Delta length must match the product count");
        }

        synchronized (mutex) {
            for (int i = 0; i < committed.length; i++) {
                long change = deltaCopy[i];
                if (change >= 0) {
                    if (committed[i] > Long.MAX_VALUE - change) {
                        throw new IllegalArgumentException("Projected quantity exceeds Long.MAX_VALUE");
                    }
                } else if (committed[i] + change < 0) {
                    throw new IllegalArgumentException("Projected quantity is negative");
                }
            }

            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter is exhausted");
            }
            long generation = lastGeneration + 1;
            lastGeneration = generation;
            staged = new StagedOperation(ownerId, generation, deltaCopy, tagsCopy);
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwnerId(ownerId);
        validateGeneration(generation);
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException("Constraint arrays must not be null");
        }
        String[] requiredCopy = copyAndValidateTags(required);
        String[] forbiddenCopy = copyAndValidateTags(forbidden);

        synchronized (mutex) {
            if (staged == null
                    || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }

            for (String tag : requiredCopy) {
                if (!contains(staged.tags, tag)) {
                    return false;
                }
            }
            for (String tag : forbiddenCopy) {
                if (contains(staged.tags, tag)) {
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

        synchronized (mutex) {
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
        synchronized (mutex) {
            long[] committedCopy = copy(committed);
            long[] projected = copy(committed);
            if (staged != null) {
                for (int i = 0; i < projected.length; i++) {
                    projected[i] += staged.delta[i];
                }
            }
            return new Snapshot(committedCopy, projected, staged);
        }
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must not be null or empty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyAndValidateTags(String[] tags) {
        String[] result = copy(tags);
        for (String tag : result) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException("Tags must not be null or empty");
            }
        }
        return result;
    }

    private static boolean contains(String[] tags, String value) {
        for (String tag : tags) {
            if (tag.equals(value)) {
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
}
