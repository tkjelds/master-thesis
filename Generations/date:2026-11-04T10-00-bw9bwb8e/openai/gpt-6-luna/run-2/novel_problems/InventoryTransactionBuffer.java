@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private long lastGeneration;

    private String stagedOwnerId;
    private long stagedGeneration;
    private long[] stagedDelta;
    private String[] stagedTags;

    public InventoryTransactionBuffer(long[] committedQuantities) {
        if (committedQuantities == null || committedQuantities.length == 0) {
            throw new IllegalArgumentException("Committed quantities must be nonempty");
        }

        this.committed = new long[committedQuantities.length];
        for (int i = 0; i < committedQuantities.length; i++) {
            long quantity = committedQuantities[i];
            if (quantity < 0) {
                throw new IllegalArgumentException("Committed quantities must be nonnegative");
            }
            this.committed[i] = quantity;
        }
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);

        synchronized (this) {
            if (delta == null || delta.length != committed.length) {
                throw new IllegalArgumentException("Delta must have one entry per product");
            }
            if (tags == null || tags.length == 0) {
                throw new IllegalArgumentException("Tags must be nonempty");
            }

            long[] copiedDelta = new long[delta.length];
            for (int i = 0; i < delta.length; i++) {
                copiedDelta[i] = delta[i];
            }

            String[] copiedTags = new String[tags.length];
            for (int i = 0; i < tags.length; i++) {
                if (tags[i] == null || tags[i].length() == 0) {
                    throw new IllegalArgumentException("Tags must not be null or empty");
                }
                copiedTags[i] = tags[i];
            }

            for (int i = 0; i < committed.length; i++) {
                if (!isValidProjectedQuantity(committed[i], copiedDelta[i])) {
                    throw new IllegalArgumentException("Staged quantity is out of range");
                }
            }

            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = lastGeneration + 1;
            lastGeneration = generation;
            stagedOwnerId = ownerId;
            stagedGeneration = generation;
            stagedDelta = copiedDelta;
            stagedTags = copiedTags;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);

        String[] copiedRequired = copyAndValidateConstraints(required);
        String[] copiedForbidden = copyAndValidateConstraints(forbidden);

        synchronized (this) {
            if (stagedOwnerId == null
                    || stagedGeneration != generation
                    || !stagedOwnerId.equals(ownerId)) {
                return false;
            }

            for (int i = 0; i < copiedRequired.length; i++) {
                if (!contains(stagedTags, copiedRequired[i])) {
                    return false;
                }
            }
            for (int i = 0; i < copiedForbidden.length; i++) {
                if (contains(stagedTags, copiedForbidden[i])) {
                    return false;
                }
            }

            for (int i = 0; i < committed.length; i++) {
                committed[i] += stagedDelta[i];
            }

            clearStagedOperation();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwner(ownerId);
        validateGeneration(generation);

        synchronized (this) {
            if (stagedOwnerId == null
                    || stagedGeneration != generation
                    || !stagedOwnerId.equals(ownerId)) {
                return false;
            }

            clearStagedOperation();
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            long[] copiedCommitted = copy(committed);
            long[] projected = new long[committed.length];

            if (stagedOwnerId == null) {
                for (int i = 0; i < committed.length; i++) {
                    projected[i] = committed[i];
                }
                return new Snapshot(copiedCommitted, projected, null, 0, null, null);
            }

            for (int i = 0; i < committed.length; i++) {
                projected[i] = committed[i] + stagedDelta[i];
            }

            return new Snapshot(
                    copiedCommitted,
                    projected,
                    stagedOwnerId,
                    stagedGeneration,
                    copy(stagedDelta),
                    copy(stagedTags));
        }
    }

    private void clearStagedOperation() {
        stagedOwnerId = null;
        stagedGeneration = 0;
        stagedDelta = null;
        stagedTags = null;
    }

    private static boolean isValidProjectedQuantity(long current, long delta) {
        if (delta < 0) {
            return delta >= -current;
        }
        return current <= Long.MAX_VALUE - delta;
    }

    private static boolean contains(String[] values, String sought) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static String[] copyAndValidateConstraints(String[] constraints) {
        if (constraints == null) {
            throw new IllegalArgumentException("Constraint arrays must not be null");
        }

        String[] result = new String[constraints.length];
        for (int i = 0; i < constraints.length; i++) {
            if (constraints[i] == null || constraints[i].length() == 0) {
                throw new IllegalArgumentException("Constraints must not be null or empty");
            }
            result[i] = constraints[i];
        }
        return result;
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must not be null or empty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
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
        public final String stagedOwnerId;
        public final long stagedGeneration;
        public final long[] stagedDelta;
        public final String[] stagedTags;

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

        public boolean hasStagedOperation() {
            return stagedOwnerId != null;
        }

        public long[] getCommittedQuantities() {
            return copy(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copy(projectedQuantities);
        }

        public long[] getStagedDelta() {
            return stagedDelta == null ? null : copy(stagedDelta);
        }

        public String[] getStagedTags() {
            return stagedTags == null ? null : copy(stagedTags);
        }
    }
}
