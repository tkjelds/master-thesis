@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration;
    private StagedOperation staged;

    public InventoryTransactionBuffer(long[] quantities) {
        if (quantities == null || quantities.length == 0) {
            throw new IllegalArgumentException("Quantities must be nonempty");
        }

        long[] copy = quantities.clone();
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantities must be nonnegative");
            }
        }
        committed = copy;
    }

    /**
     * Replaces the staging slot immediately; it never waits for an empty slot.
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException("Invalid delta length");
        }

        long[] deltaCopy = delta.clone();
        String[] tagCopy = copyTagsAsSet(tags);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                long base = committed[i];
                long change = deltaCopy[i];
                if ((change > 0 && base > Long.MAX_VALUE - change)
                        || (change < 0 && change < -base)) {
                    throw new IllegalArgumentException("Projected quantity out of range");
                }
            }

            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = lastGeneration + 1;
            StagedOperation replacement =
                    new StagedOperation(ownerId, generation, deltaCopy, tagCopy);
            lastGeneration = generation;
            staged = replacement;
            return generation;
        }
    }

    public boolean commit(
            String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyAndValidateTags(required);
        String[] forbiddenCopy = copyAndValidateTags(forbidden);

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
            long[] projected = committed.clone();

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
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must be nonempty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyAndValidateTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }
        String[] copy = tags.clone();
        for (String tag : copy) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException("Tags must be nonempty");
            }
        }
        return copy;
    }

    private static String[] copyTagsAsSet(String[] tags) {
        String[] copy = copyAndValidateTags(tags);
        int count = 0;
        for (int i = 0; i < copy.length; i++) {
            boolean duplicate = false;
            for (int j = 0; j < count; j++) {
                if (copy[j].equals(copy[i])) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                copy[count++] = copy[i];
            }
        }

        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
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

        private StagedOperation(
                String ownerId, long generation, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    /**
     * Detached, coherent view. When the slot is empty, owner, delta, and tags
     * are null and generation is zero. Array getters return fresh copies.
     */
    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final String ownerId;
        private final long generation;
        private final long[] delta;
        private final String[] tags;

        private Snapshot(
                long[] committedQuantities,
                long[] projectedQuantities,
                String ownerId,
                long generation,
                long[] delta,
                String[] tags) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }

        public long[] getCommittedQuantities() {
            return committedQuantities.clone();
        }

        public long[] getProjectedQuantities() {
            return projectedQuantities.clone();
        }

        public boolean hasStagedOperation() {
            return ownerId != null;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public long getGeneration() {
            return generation;
        }

        public long[] getDelta() {
            return delta == null ? null : delta.clone();
        }

        public String[] getTags() {
            return tags == null ? null : tags.clone();
        }
    }
}
