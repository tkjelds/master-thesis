@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private StagedOperation staged;
    private long nextGeneration = 1L;
    private boolean generationsExhausted;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Committed quantities must be a nonempty array");
        }

        long[] copy = initialQuantities.clone();
        for (long quantity : copy) {
            if (quantity < 0L) {
                throw new IllegalArgumentException(
                        "Committed quantities must be nonnegative");
            }
        }
        this.committed = copy;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        requireOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException(
                    "Delta must have one quantity per product");
        }
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }

        long[] deltaCopy = delta.clone();
        String[] tagsCopy = tags.clone();
        validateTags(tagsCopy);

        synchronized (this) {
            for (int i = 0; i < committed.length; i++) {
                if (!canAdd(committed[i], deltaCopy[i])) {
                    throw new IllegalArgumentException(
                            "Staged delta produces an invalid quantity");
                }
            }

            if (generationsExhausted) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = nextGeneration;
            if (generation == Long.MAX_VALUE) {
                generationsExhausted = true;
            } else {
                nextGeneration = generation + 1L;
            }

            staged = new StagedOperation(
                    ownerId, generation, deltaCopy, tagsCopy);
            return generation;
        }
    }

    public boolean commit(
            String ownerId,
            long generation,
            String[] required,
            String[] forbidden) {
        requireOwner(ownerId);
        requirePositiveGeneration(generation);

        if (required == null || forbidden == null) {
            throw new IllegalArgumentException(
                    "Constraint arrays must not be null");
        }

        String[] requiredCopy = required.clone();
        String[] forbiddenCopy = forbidden.clone();
        validateTags(requiredCopy);
        validateTags(forbiddenCopy);

        synchronized (this) {
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

            long[] updated = new long[committed.length];
            for (int i = 0; i < committed.length; i++) {
                updated[i] = committed[i] + staged.delta[i];
            }
            System.arraycopy(updated, 0, committed, 0, committed.length);
            staged = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        requireOwner(ownerId);
        requirePositiveGeneration(generation);

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
            long[] committedCopy = committed.clone();
            long[] projected = committed.clone();
            StagingMetadata metadata = null;

            if (staged != null) {
                for (int i = 0; i < projected.length; i++) {
                    projected[i] += staged.delta[i];
                }
                metadata = new StagingMetadata(
                        staged.ownerId,
                        staged.generation,
                        staged.delta,
                        staged.tags);
            }

            return new Snapshot(committedCopy, projected, metadata);
        }
    }

    private static void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException(
                    "Owner ID must be nonnull and nonempty");
        }
    }

    private static void requirePositiveGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "Generation must be positive");
        }
    }

    private static void validateTags(String[] tags) {
        for (String tag : tags) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(
                        "Tags must be nonnull and nonempty");
            }
        }
    }

    private static boolean contains(String[] tags, String sought) {
        for (String tag : tags) {
            if (tag.equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canAdd(long quantity, long delta) {
        if (delta >= 0L) {
            return quantity <= Long.MAX_VALUE - delta;
        }
        if (delta == Long.MIN_VALUE) {
            return false;
        }
        return quantity >= -delta;
    }

    private static final class StagedOperation {
        private final String ownerId;
        private final long generation;
        private final long[] delta;
        private final String[] tags;

        private StagedOperation(
                String ownerId, long generation, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final StagingMetadata stagingMetadata;

        private Snapshot(
                long[] committedQuantities,
                long[] projectedQuantities,
                StagingMetadata stagingMetadata) {
            this.committedQuantities = committedQuantities.clone();
            this.projectedQuantities = projectedQuantities.clone();
            this.stagingMetadata = stagingMetadata;
        }

        public long[] getCommittedQuantities() {
            return committedQuantities.clone();
        }

        public long[] getProjectedQuantities() {
            return projectedQuantities.clone();
        }

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
                String ownerId, long generation, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta.clone();
            this.tags = tags.clone();
        }

        public String getOwnerId() {
            return ownerId;
        }

        public long getGeneration() {
            return generation;
        }

        public long[] getDelta() {
            return delta.clone();
        }

        public String[] getTags() {
            return tags.clone();
        }
    }
}
