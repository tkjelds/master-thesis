@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration;
    private StagedOperation staged;

    public InventoryTransactionBuffer(long[] quantities) {
        if (quantities == null || quantities.length == 0) {
            throw new IllegalArgumentException("Quantities must be nonnull and nonempty");
        }

        long[] copy = quantities.clone();
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
        }
        committed = copy;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException("Delta must have one entry per product");
        }

        long[] deltaCopy = delta.clone();
        String[] tagSet = copyTagSet(tags);

        synchronized (lock) {
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            for (int i = 0; i < committed.length; i++) {
                long quantity = committed[i];
                long change = deltaCopy[i];
                if (change < -quantity || change > Long.MAX_VALUE - quantity) {
                    throw new IllegalArgumentException("Projected quantity is out of range");
                }
            }

            long generation = lastGeneration + 1;
            StagedOperation replacement =
                    new StagedOperation(ownerId, generation, deltaCopy, tagSet);
            staged = replacement;
            lastGeneration = generation;
            return generation;
        }
    }

    public boolean commit(
            String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyTags(required);
        String[] forbiddenCopy = copyTags(forbidden);

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
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
            long[] committedCopy = committed.clone();
            long[] projected = committedCopy.clone();

            if (staged == null) {
                return new Snapshot(committedCopy, projected, null, 0, null, null);
            }

            for (int i = 0; i < projected.length; i++) {
                projected[i] += staged.delta[i];
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

    private boolean matches(String ownerId, long generation) {
        return staged != null
                && staged.generation == generation
                && staged.ownerId.equals(ownerId);
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("Owner ID must be nonnull and nonempty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must be nonnull");
        }
        String[] copy = tags.clone();
        for (String tag : copy) {
            if (tag == null || tag.isEmpty()) {
                throw new IllegalArgumentException("Tags must be nonnull and nonempty");
            }
        }
        return copy;
    }

    private static String[] copyTagSet(String[] tags) {
        String[] copy = copyTags(tags);
        int size = 0;
        for (String tag : copy) {
            boolean found = false;
            for (int i = 0; i < size; i++) {
                if (copy[i].equals(tag)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                copy[size++] = tag;
            }
        }

        String[] result = new String[size];
        for (int i = 0; i < size; i++) {
            result[i] = copy[i];
        }
        return result;
    }

    private static boolean contains(String[] tags, String target) {
        for (String tag : tags) {
            if (tag.equals(target)) {
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

        // When the slot is empty, the owner, delta, and tags are null and the generation is zero.
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
