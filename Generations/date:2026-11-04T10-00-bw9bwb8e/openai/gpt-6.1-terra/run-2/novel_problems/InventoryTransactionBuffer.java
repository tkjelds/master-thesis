@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;

    // Guarded by lock.
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

    /**
     * Arrays in a snapshot are detached from the buffer. When no operation is
     * staged, stagedGeneration is zero and the other staged fields are null.
     */
    public static final class Snapshot {
        public final long[] committedQuantities;
        public final long[] projectedQuantities;
        public final boolean hasStagedOperation;
        public final String stagedOwnerId;
        public final long stagedGeneration;
        public final long[] stagedDelta;
        public final String[] stagedTags;

        private Snapshot(long[] committedQuantities, long[] projectedQuantities,
                         boolean hasStagedOperation, String stagedOwnerId,
                         long stagedGeneration, long[] stagedDelta,
                         String[] stagedTags) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.hasStagedOperation = hasStagedOperation;
            this.stagedOwnerId = stagedOwnerId;
            this.stagedGeneration = stagedGeneration;
            this.stagedDelta = stagedDelta;
            this.stagedTags = stagedTags;
        }
    }

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Quantities must be nonempty");
        }
        long[] copy = initialQuantities.clone();
        for (long quantity : copy) {
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative committed quantity");
            }
        }
        committed = copy;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException("Invalid delta length");
        }
        long[] deltaCopy = delta.clone();
        String[] tagCopy = uniqueTags(tags);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                long base = committed[i];
                long change = deltaCopy[i];
                if (change >= 0) {
                    if (base > Long.MAX_VALUE - change) {
                        throw new IllegalArgumentException("Projected quantity overflow");
                    }
                } else if (change < -base) {
                    throw new IllegalArgumentException("Negative projected quantity");
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

    public boolean commit(String ownerId, long generation,
                          String[] required, String[] forbidden) {
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
                return new Snapshot(committedCopy, projected, false,
                                    null, 0, null, null);
            }

            for (int i = 0; i < projected.length; i++) {
                projected[i] += staged.delta[i];
            }
            return new Snapshot(committedCopy, projected, true,
                                staged.ownerId, staged.generation,
                                staged.delta.clone(), staged.tags.clone());
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

    private static String[] uniqueTags(String[] tags) {
        String[] copy = copyAndValidateTags(tags);
        int count = 0;
        for (int i = 0; i < copy.length; i++) {
            String tag = copy[i];
            boolean found = false;
            for (int j = 0; j < count; j++) {
                if (copy[j].equals(tag)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                copy[count++] = tag;
            }
        }
        if (count == copy.length) {
            return copy;
        }
        String[] unique = new String[count];
        for (int i = 0; i < count; i++) {
            unique[i] = copy[i];
        }
        return unique;
    }

    private static boolean contains(String[] tags, String target) {
        for (String tag : tags) {
            if (tag.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
