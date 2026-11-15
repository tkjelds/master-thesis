@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committedQuantities;
    private StagedOperation stagedOperation;
    private long nextGeneration = 1L;
    private boolean generationExhausted;

    public InventoryTransactionBuffer(long[] initialCommittedQuantities) {
        if (initialCommittedQuantities == null
                || initialCommittedQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Committed quantities must be nonempty");
        }

        committedQuantities = copy(initialCommittedQuantities);
        for (long quantity : committedQuantities) {
            if (quantity < 0L) {
                throw new IllegalArgumentException(
                        "Committed quantities must be nonnegative");
            }
        }
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwnerId(ownerId);
        if (delta == null || tags == null) {
            throw new IllegalArgumentException("Delta and tags must not be null");
        }
        if (delta.length != committedQuantities.length) {
            throw new IllegalArgumentException(
                    "Delta must have one quantity per product");
        }

        long[] deltaCopy = copy(delta);
        String[] tagsCopy = copyAndValidateTags(tags, "tags");

        synchronized (lock) {
            for (int i = 0; i < committedQuantities.length; i++) {
                checkedProjectedQuantity(committedQuantities[i], deltaCopy[i]);
            }
            if (generationExhausted) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = nextGeneration;
            if (generation == Long.MAX_VALUE) {
                generationExhausted = true;
            } else {
                nextGeneration = generation + 1L;
            }
            stagedOperation = new StagedOperation(
                    ownerId, generation, deltaCopy, tagsCopy);
            return generation;
        }
    }

    public boolean commit(
            String ownerId,
            long generation,
            String[] required,
            String[] forbidden) {
        validateOwnerId(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyAndValidateTags(required, "required");
        String[] forbiddenCopy = copyAndValidateTags(forbidden, "forbidden");

        synchronized (lock) {
            StagedOperation operation = stagedOperation;
            if (operation == null
                    || operation.generation != generation
                    || !operation.ownerId.equals(ownerId)) {
                return false;
            }

            for (String tag : requiredCopy) {
                if (!contains(operation.tags, tag)) {
                    return false;
                }
            }
            for (String tag : forbiddenCopy) {
                if (contains(operation.tags, tag)) {
                    return false;
                }
            }

            for (int i = 0; i < committedQuantities.length; i++) {
                committedQuantities[i] += operation.delta[i];
            }
            stagedOperation = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwnerId(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
            if (stagedOperation == null
                    || stagedOperation.generation != generation
                    || !stagedOperation.ownerId.equals(ownerId)) {
                return false;
            }
            stagedOperation = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = copy(committedQuantities);
            long[] projectedCopy = copy(committedQuantities);
            StagingMetadata metadata = null;

            if (stagedOperation != null) {
                for (int i = 0; i < projectedCopy.length; i++) {
                    projectedCopy[i] = checkedProjectedQuantity(
                            projectedCopy[i], stagedOperation.delta[i]);
                }
                metadata = new StagingMetadata(
                        stagedOperation.ownerId,
                        stagedOperation.generation,
                        stagedOperation.delta,
                        stagedOperation.tags);
            }

            return new Snapshot(committedCopy, projectedCopy, metadata);
        }
    }

    private static long checkedProjectedQuantity(long committed, long delta) {
        if (delta > 0L && committed > Long.MAX_VALUE - delta) {
            throw new IllegalArgumentException(
                    "Projected quantity exceeds Long.MAX_VALUE");
        }
        if (delta < 0L && delta < -committed) {
            throw new IllegalArgumentException(
                    "Projected quantity cannot be negative");
        }
        return committed + delta;
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must not be null or empty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyAndValidateTags(String[] tags, String name) {
        if (tags == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String[] result = copy(tags);
        for (String tag : result) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(
                        name + " must not contain null or empty tags");
            }
        }
        return result;
    }

    private static boolean contains(String[] tags, String sought) {
        for (String tag : tags) {
            if (tag.equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static long[] copy(long[] source) {
        long[] result = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copy(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
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
            this.committedQuantities = copy(committedQuantities);
            this.projectedQuantities = copy(projectedQuantities);
            this.stagingMetadata = stagingMetadata;
        }

        public long[] getCommittedQuantities() {
            return copy(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copy(projectedQuantities);
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
}
