@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration;
    private StagedOperation staged;

    public InventoryTransactionBuffer(long[] initialCommitted) {
        if (initialCommitted == null || initialCommitted.length == 0) {
            throw new IllegalArgumentException("Committed quantities must be nonempty");
        }

        committed = initialCommitted.clone();
        for (long quantity : committed) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Committed quantities must be nonnegative");
            }
        }
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || tags == null) {
            throw new IllegalArgumentException("Delta and tags must not be null");
        }

        long[] copiedDelta = delta.clone();
        String[] copiedTags = tags.clone();
        validateTags(copiedTags, "tags");

        synchronized (lock) {
            if (copiedDelta.length != committed.length) {
                throw new IllegalArgumentException("Delta length must match the product count");
            }
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter is exhausted");
            }

            for (int i = 0; i < committed.length; i++) {
                long change = copiedDelta[i];
                if (change > 0 && committed[i] > Long.MAX_VALUE - change) {
                    throw new IllegalArgumentException("Projected quantity exceeds Long.MAX_VALUE");
                }
                if (committed[i] + change < 0) {
                    throw new IllegalArgumentException("Projected quantity must be nonnegative");
                }
            }

            long generation = lastGeneration + 1;
            staged = new StagedOperation(ownerId, generation, copiedDelta, copiedTags);
            lastGeneration = generation;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException("Constraints must not be null");
        }

        String[] copiedRequired = required.clone();
        String[] copiedForbidden = forbidden.clone();
        validateTags(copiedRequired, "required");
        validateTags(copiedForbidden, "forbidden");

        synchronized (lock) {
            if (staged == null
                    || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }

            for (String requiredTag : copiedRequired) {
                if (!contains(staged.tags, requiredTag)) {
                    return false;
                }
            }
            for (String forbiddenTag : copiedForbidden) {
                if (contains(staged.tags, forbiddenTag)) {
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
        validateOwner(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
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
        synchronized (lock) {
            long[] committedCopy = committed.clone();
            long[] projected = new long[committed.length];
            for (int i = 0; i < committed.length; i++) {
                projected[i] = committed[i] + (staged == null ? 0 : staged.delta[i]);
            }

            if (staged == null) {
                return new Snapshot(committedCopy, projected, null, 0, null, null);
            }
            return new Snapshot(
                    committedCopy,
                    projected,
                    staged.ownerId,
                    staged.generation,
                    staged.delta.clone(),
                    staged.tags.clone());
        }
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must be nonempty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static void validateTags(String[] tags, String name) {
        for (String tag : tags) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(name + " must contain only nonempty tags");
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

    private static final class StagedOperation {
        private final String ownerId;
        private final long generation;
        private final long[] delta;
        private final String[] tags;

        private StagedOperation(String ownerId, long generation, long[] delta, String[] tags) {
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

        private Snapshot(
                long[] committedQuantities,
                long[] projectedQuantities,
                String stagedOwnerId,
                long stagedGeneration,
                long[] stagedDelta,
                String[] stagedTags) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.stagedOwnerId = stagedOwnerId;
            this.stagedGeneration = stagedGeneration;
            this.stagedDelta = stagedDelta;
            this.stagedTags = stagedTags;
        }

        public long[] getCommittedQuantities() {
            return committedQuantities.clone();
        }

        public long[] getProjectedQuantities() {
            return projectedQuantities.clone();
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
            return stagedDelta == null ? null : stagedDelta.clone();
        }

        public String[] getStagedTags() {
            return stagedTags == null ? null : stagedTags.clone();
        }
    }
}
