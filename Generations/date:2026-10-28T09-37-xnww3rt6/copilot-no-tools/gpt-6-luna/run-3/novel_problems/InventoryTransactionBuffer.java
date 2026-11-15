@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    public static final class StagingMetadata {
        public final String ownerId;
        public final long generation;
        public final long[] delta;
        public final String[] tags;

        private StagingMetadata(String ownerId, long generation, long[] delta,
                                String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = copy(delta);
            this.tags = copy(tags);
        }

        public String getOwnerId() {
            return ownerId;
        }

        public long getGeneration() {
            return generation;
        }

        public long[] getDelta() {
            return copy(delta);
        }

        public String[] getTags() {
            return copy(tags);
        }
    }

    public static final class Snapshot {
        public final long[] committedQuantities;
        public final long[] projectedQuantities;
        public final StagingMetadata stagingMetadata;

        private Snapshot(long[] committedQuantities,
                         long[] projectedQuantities,
                         StagingMetadata stagingMetadata) {
            this.committedQuantities = copy(committedQuantities);
            this.projectedQuantities = copy(projectedQuantities);
            this.stagingMetadata = stagingMetadata == null ? null
                    : new StagingMetadata(stagingMetadata.ownerId,
                    stagingMetadata.generation, stagingMetadata.delta,
                    stagingMetadata.tags);
        }

        public long[] getCommittedQuantities() {
            return copy(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copy(projectedQuantities);
        }

        public StagingMetadata getStagingMetadata() {
            return stagingMetadata == null ? null
                    : new StagingMetadata(stagingMetadata.ownerId,
                    stagingMetadata.generation, stagingMetadata.delta,
                    stagingMetadata.tags);
        }
    }

    private static final class StagedOperation {
        final String ownerId;
        final long generation;
        final long[] delta;
        final String[] tags;

        StagedOperation(String ownerId, long generation, long[] delta,
                        String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    private final Object lock = new Object();
    private final long[] committed;
    private StagedOperation staged;
    private long lastGeneration;

    public InventoryTransactionBuffer(long[] initialCommittedQuantities) {
        if (initialCommittedQuantities == null
                || initialCommittedQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "initial committed quantities must be nonempty");
        }

        long[] initial = copy(initialCommittedQuantities);
        for (int i = 0; i < initial.length; i++) {
            if (initial[i] < 0) {
                throw new IllegalArgumentException(
                        "committed quantities must be nonnegative");
            }
        }
        committed = initial;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || tags == null) {
            throw new IllegalArgumentException("delta and tags must not be null");
        }

        long[] detachedDelta = copy(delta);
        String[] detachedTags = copyAsSet(tags);
        if (detachedDelta.length != committed.length) {
            throw new IllegalArgumentException(
                    "delta must contain one quantity per product");
        }

        synchronized (lock) {
            validateProjection(detachedDelta);
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter exhausted");
            }

            long generation = lastGeneration + 1;
            staged = new StagedOperation(ownerId, generation, detachedDelta,
                    detachedTags);
            lastGeneration = generation;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required,
                          String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] detachedRequired = copyAsSet(required);
        String[] detachedForbidden = copyAsSet(forbidden);

        synchronized (lock) {
            if (staged == null || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }

            for (int i = 0; i < detachedRequired.length; i++) {
                if (!contains(staged.tags, detachedRequired[i])) {
                    return false;
                }
            }
            for (int i = 0; i < detachedForbidden.length; i++) {
                if (contains(staged.tags, detachedForbidden[i])) {
                    return false;
                }
            }

            long[] updated = new long[committed.length];
            for (int i = 0; i < committed.length; i++) {
                long value = committed[i];
                long change = staged.delta[i];
                if (!isValidProjection(value, change)) {
                    throw new IllegalStateException(
                            "staged operation would violate quantity bounds");
                }
                updated[i] = value + change;
            }

            for (int i = 0; i < committed.length; i++) {
                committed[i] = updated[i];
            }
            staged = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwner(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
            if (staged == null || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }
            staged = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] projected = copy(committed);
            StagingMetadata metadata = null;
            if (staged != null) {
                for (int i = 0; i < projected.length; i++) {
                    projected[i] += staged.delta[i];
                }
                metadata = new StagingMetadata(staged.ownerId,
                        staged.generation, staged.delta, staged.tags);
            }
            return new Snapshot(committed, projected, metadata);
        }
    }

    private void validateProjection(long[] delta) {
        for (int i = 0; i < committed.length; i++) {
            if (!isValidProjection(committed[i], delta[i])) {
                throw new IllegalArgumentException(
                        "projected quantities must be between zero and Long.MAX_VALUE");
            }
        }
    }

    private static boolean isValidProjection(long quantity, long delta) {
        if (delta >= 0) {
            return quantity <= Long.MAX_VALUE - delta;
        }
        return delta >= -quantity;
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException(
                    "ownerId must not be null or empty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
    }

    private static String[] copyAsSet(String[] values) {
        if (values == null) {
            throw new IllegalArgumentException("tag array must not be null");
        }

        String[] unique = new String[values.length];
        int size = 0;
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            if (value == null || value.length() == 0) {
                throw new IllegalArgumentException(
                        "tags must not be null or empty");
            }
            if (!contains(unique, size, value)) {
                unique[size++] = value;
            }
        }

        String[] result = new String[size];
        for (int i = 0; i < size; i++) {
            result[i] = unique[i];
        }
        return result;
    }

    private static boolean contains(String[] values, String target) {
        return contains(values, values.length, target);
    }

    private static boolean contains(String[] values, int size, String target) {
        for (int i = 0; i < size; i++) {
            if (values[i].equals(target)) {
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
}
