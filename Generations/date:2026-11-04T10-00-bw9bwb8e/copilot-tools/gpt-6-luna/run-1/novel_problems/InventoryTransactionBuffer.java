@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private StagedOperation staged;
    private long nextGeneration = 1L;
    private boolean generationsExhausted;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Initial quantities must be nonempty");
        }

        committed = initialQuantities.clone();
        for (long quantity : committed) {
            if (quantity < 0L) {
                throw new IllegalArgumentException("Initial quantities must be nonnegative");
            }
        }
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwnerId(ownerId);
        if (delta == null || tags == null) {
            throw new IllegalArgumentException("Delta and tags must not be null");
        }

        long[] copiedDelta = delta.clone();
        String[] copiedTags = copyAndValidateTags(tags);
        if (copiedDelta.length != committed.length) {
            throw new IllegalArgumentException("Delta length must match the product count");
        }

        synchronized (lock) {
            if (generationsExhausted) {
                throw new IllegalStateException("Generation counter is exhausted");
            }
            if (!isValidProjection(committed, copiedDelta)) {
                throw new IllegalArgumentException("Projected quantities are out of range");
            }

            long generation = nextGeneration;
            if (generation == Long.MAX_VALUE) {
                generationsExhausted = true;
            } else {
                nextGeneration = generation + 1L;
            }
            staged = new StagedOperation(ownerId, generation, copiedDelta, copiedTags);
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        validateOwnerId(ownerId);
        validateGeneration(generation);
        String[] copiedRequired = copyAndValidateTags(required);
        String[] copiedForbidden = copyAndValidateTags(forbidden);

        synchronized (lock) {
            if (staged == null
                    || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }

            for (String tag : copiedRequired) {
                if (!contains(staged.tags, tag)) {
                    return false;
                }
            }
            for (String tag : copiedForbidden) {
                if (contains(staged.tags, tag)) {
                    return false;
                }
            }

            if (!isValidProjection(committed, staged.delta)) {
                throw new IllegalStateException("Staged projection is out of range");
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += staged.delta[i];
            }
            staged = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwnerId(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
            if (staged == null
                    || staged.generation != generation
                    || !staged.ownerId.equals(ownerId)) {
                return false;
            }
            staged = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] copiedCommitted = committed.clone();
            long[] projected = copiedCommitted.clone();
            String ownerId = null;
            long generation = 0L;
            long[] delta = null;
            String[] tags = null;
            boolean hasStagedOperation = staged != null;

            if (hasStagedOperation) {
                ownerId = staged.ownerId;
                generation = staged.generation;
                delta = staged.delta.clone();
                tags = staged.tags.clone();
                for (int i = 0; i < projected.length; i++) {
                    projected[i] += staged.delta[i];
                }
            }

            return new Snapshot(
                    hasStagedOperation,
                    ownerId,
                    generation,
                    copiedCommitted,
                    projected,
                    delta,
                    tags);
        }
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("Owner ID must be nonempty");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static String[] copyAndValidateTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }
        String[] copy = tags.clone();
        for (String tag : copy) {
            if (tag == null || tag.isEmpty()) {
                throw new IllegalArgumentException("Tags must be nonempty");
            }
        }
        return copy;
    }

    private static boolean contains(String[] tags, String sought) {
        for (String tag : tags) {
            if (tag.equals(sought)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidProjection(long[] quantities, long[] delta) {
        for (int i = 0; i < quantities.length; i++) {
            long quantity = quantities[i];
            long change = delta[i];
            long result = quantity + change;
            if (((quantity ^ result) & (change ^ result)) < 0L || result < 0L) {
                return false;
            }
        }
        return true;
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
        private final boolean hasStagedOperation;
        private final String stagingOwnerId;
        private final long stagingGeneration;
        private final long[] committed;
        private final long[] projected;
        private final long[] stagingDelta;
        private final String[] stagingTags;

        private Snapshot(
                boolean hasStagedOperation,
                String stagingOwnerId,
                long stagingGeneration,
                long[] committed,
                long[] projected,
                long[] stagingDelta,
                String[] stagingTags) {
            this.hasStagedOperation = hasStagedOperation;
            this.stagingOwnerId = stagingOwnerId;
            this.stagingGeneration = stagingGeneration;
            this.committed = committed.clone();
            this.projected = projected.clone();
            this.stagingDelta = stagingDelta == null ? null : stagingDelta.clone();
            this.stagingTags = stagingTags == null ? null : stagingTags.clone();
        }

        public boolean hasStagedOperation() {
            return hasStagedOperation;
        }

        public String getStagingOwnerId() {
            return stagingOwnerId;
        }

        public long getStagingGeneration() {
            return stagingGeneration;
        }

        public long[] getCommitted() {
            return committed.clone();
        }

        public long[] getProjected() {
            return projected.clone();
        }

        public long[] getStagingDelta() {
            return stagingDelta == null ? null : stagingDelta.clone();
        }

        public String[] getStagingTags() {
            return stagingTags == null ? null : stagingTags.clone();
        }
    }
}
