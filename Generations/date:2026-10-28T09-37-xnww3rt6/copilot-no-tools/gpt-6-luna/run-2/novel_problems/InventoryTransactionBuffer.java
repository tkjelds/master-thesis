@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private long[] committed;
    private long lastGeneration;
    private String stagedOwnerId;
    private long stagedGeneration;
    private long[] stagedDelta;
    private long[] stagedProjected;
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
        validateOwnerId(ownerId);
        if (delta == null) {
            throw new IllegalArgumentException("Delta must not be null");
        }

        long[] deltaCopy = delta.clone();
        String[] tagCopy = copyTagSet(tags, "tags");

        synchronized (lock) {
            if (deltaCopy.length != committed.length) {
                throw new IllegalArgumentException("Delta length must match the product count");
            }

            long[] projected = new long[committed.length];
            for (int i = 0; i < committed.length; i++) {
                projected[i] = addQuantity(committed[i], deltaCopy[i]);
            }

            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter is exhausted");
            }

            long generation = lastGeneration + 1;
            lastGeneration = generation;
            stagedOwnerId = ownerId;
            stagedGeneration = generation;
            stagedDelta = deltaCopy;
            stagedProjected = projected;
            stagedTags = tagCopy;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwnerId(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyTagSet(required, "required");
        String[] forbiddenCopy = copyTagSet(forbidden, "forbidden");

        synchronized (lock) {
            if (stagedOwnerId == null
                    || !stagedOwnerId.equals(ownerId)
                    || stagedGeneration != generation) {
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

            committed = stagedProjected;
            clearStaged();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwnerId(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
            if (stagedOwnerId == null
                    || !stagedOwnerId.equals(ownerId)
                    || stagedGeneration != generation) {
                return false;
            }

            clearStaged();
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = committed.clone();
            if (stagedOwnerId == null) {
                return new Snapshot(
                        committedCopy,
                        committedCopy.clone(),
                        false,
                        null,
                        0,
                        new long[0],
                        new String[0]);
            }

            return new Snapshot(
                    committedCopy,
                    stagedProjected.clone(),
                    true,
                    stagedOwnerId,
                    stagedGeneration,
                    stagedDelta.clone(),
                    stagedTags.clone());
        }
    }

    private void clearStaged() {
        stagedOwnerId = null;
        stagedGeneration = 0;
        stagedDelta = null;
        stagedProjected = null;
        stagedTags = null;
    }

    private static long addQuantity(long quantity, long delta) {
        if (delta < 0) {
            if (delta == Long.MIN_VALUE || -delta > quantity) {
                throw new IllegalArgumentException("Projected quantity would be negative");
            }
        } else if (delta > Long.MAX_VALUE - quantity) {
            throw new IllegalArgumentException("Projected quantity would overflow");
        }
        return quantity + delta;
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must be nonempty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyTagSet(String[] tags, String name) {
        if (tags == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }

        String[] copy = tags.clone();
        String[] unique = new String[copy.length];
        int count = 0;

        for (String tag : copy) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(name + " must contain only nonempty tags");
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
        System.arraycopy(unique, 0, result, 0, count);
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

    public static final class Snapshot {
        public final long[] committed;
        public final long[] projected;
        public final boolean hasStagedOperation;
        public final String stagedOwnerId;
        public final long stagedGeneration;
        public final long[] stagedDelta;
        public final String[] stagedTags;

        private Snapshot(
                long[] committed,
                long[] projected,
                boolean hasStagedOperation,
                String stagedOwnerId,
                long stagedGeneration,
                long[] stagedDelta,
                String[] stagedTags) {
            this.committed = committed;
            this.projected = projected;
            this.hasStagedOperation = hasStagedOperation;
            this.stagedOwnerId = stagedOwnerId;
            this.stagedGeneration = stagedGeneration;
            this.stagedDelta = stagedDelta;
            this.stagedTags = stagedTags;
        }
    }
}
