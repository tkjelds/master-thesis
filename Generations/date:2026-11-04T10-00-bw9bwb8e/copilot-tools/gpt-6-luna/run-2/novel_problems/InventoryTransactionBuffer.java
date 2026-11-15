@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private String stagedOwnerId;
    private long stagedGeneration;
    private long[] stagedDelta;
    private String[] stagedTags;
    private long nextGeneration = 1L;
    private boolean generationsExhausted;

    public InventoryTransactionBuffer(long[] initialCommitted) {
        if (initialCommitted == null || initialCommitted.length == 0) {
            throw new IllegalArgumentException("Committed quantities must be nonempty");
        }

        committed = copy(initialCommitted);
        for (long quantity : committed) {
            if (quantity < 0L) {
                throw new IllegalArgumentException("Committed quantities must be nonnegative");
            }
        }
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        synchronized (lock) {
            validateOwnerId(ownerId);
            if (delta == null || delta.length != committed.length) {
                throw new IllegalArgumentException("Delta length must match the product count");
            }
            if (tags == null) {
                throw new IllegalArgumentException("Tags must not be null");
            }

            long[] deltaCopy = copy(delta);
            String[] tagsCopy = copy(tags);
            for (String tag : tagsCopy) {
                validateTag(tag);
            }

            for (int i = 0; i < committed.length; i++) {
                checkedAdd(committed[i], deltaCopy[i]);
            }

            if (generationsExhausted) {
                throw new IllegalStateException("Generation counter is exhausted");
            }

            long generation = nextGeneration;
            if (generation == Long.MAX_VALUE) {
                generationsExhausted = true;
            } else {
                nextGeneration = generation + 1L;
            }

            stagedOwnerId = ownerId;
            stagedGeneration = generation;
            stagedDelta = deltaCopy;
            stagedTags = tagsCopy;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        synchronized (lock) {
            validateOwnerId(ownerId);
            validateGeneration(generation);
            String[] requiredCopy = copyAndValidateConstraints(required);
            String[] forbiddenCopy = copyAndValidateConstraints(forbidden);

            if (stagedDelta == null
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

            for (int i = 0; i < committed.length; i++) {
                committed[i] = checkedAdd(committed[i], stagedDelta[i]);
            }
            clearStagedOperation();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        synchronized (lock) {
            validateOwnerId(ownerId);
            validateGeneration(generation);

            if (stagedDelta == null
                    || !stagedOwnerId.equals(ownerId)
                    || stagedGeneration != generation) {
                return false;
            }

            clearStagedOperation();
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = copy(committed);
            long[] projected = copy(committed);
            if (stagedDelta != null) {
                for (int i = 0; i < projected.length; i++) {
                    projected[i] = checkedAdd(projected[i], stagedDelta[i]);
                }
            }

            return new Snapshot(
                    committedCopy,
                    projected,
                    stagedDelta != null,
                    stagedOwnerId,
                    stagedGeneration,
                    stagedDelta == null ? null : copy(stagedDelta),
                    stagedTags == null ? null : copy(stagedTags));
        }
    }

    private void clearStagedOperation() {
        stagedOwnerId = null;
        stagedGeneration = 0L;
        stagedDelta = null;
        stagedTags = null;
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must be nonempty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static void validateTag(String tag) {
        if (tag == null || tag.length() == 0) {
            throw new IllegalArgumentException("Tags must be nonempty");
        }
    }

    private static String[] copyAndValidateConstraints(String[] values) {
        if (values == null) {
            throw new IllegalArgumentException("Constraint arrays must not be null");
        }
        String[] result = copy(values);
        for (String value : result) {
            validateTag(value);
        }
        return result;
    }

    private static long checkedAdd(long quantity, long delta) {
        if (delta < 0L) {
            if (delta < -quantity) {
                throw new IllegalArgumentException("Projected quantity would be negative");
            }
        } else if (quantity > Long.MAX_VALUE - delta) {
            throw new IllegalArgumentException("Projected quantity would exceed Long.MAX_VALUE");
        }
        return quantity + delta;
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) {
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static long[] copy(long[] values) {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static String[] copy(String[] values) {
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    public static final class Snapshot {
        public final long[] committedQuantities;
        public final long[] projectedQuantities;
        public final boolean hasStagedOperation;
        public final String stagedOwnerId;
        public final long stagedGeneration;
        public final long[] stagedDelta;
        public final String[] stagedTags;

        private Snapshot(
                long[] committedQuantities,
                long[] projectedQuantities,
                boolean hasStagedOperation,
                String stagedOwnerId,
                long stagedGeneration,
                long[] stagedDelta,
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
}
