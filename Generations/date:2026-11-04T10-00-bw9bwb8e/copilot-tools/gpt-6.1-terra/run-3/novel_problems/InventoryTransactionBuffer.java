@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;

    private long lastGeneration;
    private String stagedOwnerId;
    private long stagedGeneration;
    private long[] stagedDelta;
    private String[] stagedTags;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Initial quantities must be nonempty");
        }

        long[] copy = initialQuantities.clone();
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Initial quantities must be nonnegative");
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
        String[] tagsCopy = copyTagSet(tags);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                long quantity = committed[i];
                long change = deltaCopy[i];
                if (change < -quantity || change > Long.MAX_VALUE - quantity) {
                    throw new IllegalArgumentException("Projected quantity is out of range");
                }
            }

            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = lastGeneration + 1;
            stagedOwnerId = ownerId;
            stagedGeneration = generation;
            stagedDelta = deltaCopy;
            stagedTags = tagsCopy;
            lastGeneration = generation;
            return generation;
        }
    }

    public boolean commit(
            String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyTagSet(required);
        String[] forbiddenCopy = copyTagSet(forbidden);

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
                return false;
            }

            for (String tag : requiredCopy) {
                if (!contains(stagedTags, tag)) {
                    return false;
                }
            }
            for (String tag : forbiddenCopy) {
                if (contains(stagedTags, tag)) {
                    return false;
                }
            }

            for (int i = 0; i < committed.length; i++) {
                committed[i] += stagedDelta[i];
            }
            clearSlot();
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
            clearSlot();
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = committed.clone();
            long[] projectedCopy = committed.clone();
            boolean hasStagedOperation = stagedDelta != null;

            if (hasStagedOperation) {
                for (int i = 0; i < projectedCopy.length; i++) {
                    projectedCopy[i] += stagedDelta[i];
                }
            }

            return new Snapshot(
                    committedCopy,
                    projectedCopy,
                    hasStagedOperation,
                    stagedOwnerId,
                    stagedGeneration,
                    hasStagedOperation ? stagedDelta.clone() : null,
                    hasStagedOperation ? stagedTags.clone() : new String[0]);
        }
    }

    /**
     * Arrays belong to this snapshot and are detached from the buffer.
     * When the slot is empty, ownerId and delta are null, generation is zero,
     * and tags is empty.
     */
    public static final class Snapshot {
        public final long[] committed;
        public final long[] projected;
        public final boolean hasStagedOperation;
        public final String ownerId;
        public final long generation;
        public final long[] delta;
        public final String[] tags;

        private Snapshot(
                long[] committed,
                long[] projected,
                boolean hasStagedOperation,
                String ownerId,
                long generation,
                long[] delta,
                String[] tags) {
            this.committed = committed;
            this.projected = projected;
            this.hasStagedOperation = hasStagedOperation;
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
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

    private static String[] copyTagSet(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }

        String[] inputCopy = tags.clone();
        String[] unique = new String[inputCopy.length];
        int count = 0;

        for (String tag : inputCopy) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException("Tags must be nonempty");
            }

            boolean found = false;
            for (int i = 0; i < count; i++) {
                if (unique[i].equals(tag)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                unique[count++] = tag;
            }
        }

        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = unique[i];
        }
        return result;
    }

    private static boolean contains(String[] tags, String tag) {
        for (String candidate : tags) {
            if (candidate.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    // Called only while holding lock.
    private boolean matches(String ownerId, long generation) {
        return stagedDelta != null
                && stagedGeneration == generation
                && stagedOwnerId.equals(ownerId);
    }

    // Called only while holding lock.
    private void clearSlot() {
        stagedOwnerId = null;
        stagedGeneration = 0;
        stagedDelta = null;
        stagedTags = null;
    }
}
