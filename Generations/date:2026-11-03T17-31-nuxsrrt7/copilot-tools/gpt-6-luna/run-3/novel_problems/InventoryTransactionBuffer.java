@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committedQuantities;
    private long generationCounter;
    private String stagingOwnerId;
    private long stagingGeneration;
    private long[] stagingDelta;
    private String[] stagingTags;

    public InventoryTransactionBuffer(long[] initialCommittedQuantities) {
        if (initialCommittedQuantities == null
                || initialCommittedQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Committed quantities must be a nonempty array");
        }

        committedQuantities = initialCommittedQuantities.clone();
        for (long quantity : committedQuantities) {
            if (quantity < 0) {
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

        long[] copiedDelta = delta.clone();
        String[] copiedTags = tags.clone();
        validateTags(copiedTags);

        synchronized (lock) {
            validateProjectedQuantities(copiedDelta);
            if (generationCounter == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = generationCounter + 1;
            generationCounter = generation;
            stagingOwnerId = ownerId;
            stagingGeneration = generation;
            stagingDelta = copiedDelta;
            stagingTags = copiedTags;
            return generation;
        }
    }

    public boolean commit(
            String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwnerId(ownerId);
        validateGeneration(generation);
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException(
                    "Required and forbidden tags must not be null");
        }

        String[] copiedRequired = required.clone();
        String[] copiedForbidden = forbidden.clone();
        validateTags(copiedRequired);
        validateTags(copiedForbidden);

        synchronized (lock) {
            if (stagingDelta == null
                    || !stagingOwnerId.equals(ownerId)
                    || stagingGeneration != generation) {
                return false;
            }

            for (String tag : copiedRequired) {
                if (!containsTag(stagingTags, tag)) {
                    return false;
                }
            }
            for (String tag : copiedForbidden) {
                if (containsTag(stagingTags, tag)) {
                    return false;
                }
            }

            for (int i = 0; i < committedQuantities.length; i++) {
                committedQuantities[i] += stagingDelta[i];
            }
            clearStagingSlot();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwnerId(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
            if (stagingDelta == null
                    || !stagingOwnerId.equals(ownerId)
                    || stagingGeneration != generation) {
                return false;
            }

            clearStagingSlot();
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = committedQuantities.clone();
            long[] projectedCopy = committedQuantities.clone();
            String ownerCopy = stagingOwnerId;
            long generationCopy = stagingGeneration;
            long[] deltaCopy = null;
            String[] tagsCopy = null;

            if (stagingDelta != null) {
                deltaCopy = stagingDelta.clone();
                tagsCopy = stagingTags.clone();
                for (int i = 0; i < projectedCopy.length; i++) {
                    projectedCopy[i] += stagingDelta[i];
                }
            }

            return new Snapshot(
                    committedCopy,
                    projectedCopy,
                    ownerCopy,
                    generationCopy,
                    deltaCopy,
                    tagsCopy);
        }
    }

    private void validateProjectedQuantities(long[] delta) {
        for (int i = 0; i < committedQuantities.length; i++) {
            long committed = committedQuantities[i];
            long change = delta[i];
            if ((change > 0 && change > Long.MAX_VALUE - committed)
                    || (change < 0 && change < -committed)) {
                throw new IllegalArgumentException(
                        "Delta produces an invalid projected quantity");
            }
        }
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must not be null or empty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static void validateTags(String[] tags) {
        for (String tag : tags) {
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(
                        "Tags must not be null or empty");
            }
        }
    }

    private static boolean containsTag(String[] tags, String candidate) {
        for (String tag : tags) {
            if (tag.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void clearStagingSlot() {
        stagingOwnerId = null;
        stagingGeneration = 0;
        stagingDelta = null;
        stagingTags = null;
    }

    public static final class Snapshot {
        public final long[] committedQuantities;
        public final long[] projectedQuantities;
        public final String stagingOwnerId;
        public final long stagingGeneration;
        public final long[] stagingDelta;
        public final String[] stagingTags;

        private Snapshot(
                long[] committedQuantities,
                long[] projectedQuantities,
                String stagingOwnerId,
                long stagingGeneration,
                long[] stagingDelta,
                String[] stagingTags) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.stagingOwnerId = stagingOwnerId;
            this.stagingGeneration = stagingGeneration;
            this.stagingDelta = stagingDelta;
            this.stagingTags = stagingTags;
        }
    }
}
