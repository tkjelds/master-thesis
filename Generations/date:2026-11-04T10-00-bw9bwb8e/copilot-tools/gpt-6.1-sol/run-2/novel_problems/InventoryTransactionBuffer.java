@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration;
    private StagedOperation staged;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException(
                    "Initial quantities must be nonnull and nonempty.");
        }

        long[] copy = copy(initialQuantities);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException(
                        "Committed quantities must be nonnegative.");
            }
        }
        committed = copy;
    }

    /**
     * Replaces the staging slot without waiting for it to become empty.
     * Tags must contain at least one nonnull, nonempty string.
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException(
                    "Delta must contain one quantity per product.");
        }

        long[] deltaCopy = copy(delta);
        String[] tagCopy = validatedTags(tags, false);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                checkedProjection(committed[i], deltaCopy[i]);
            }
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted.");
            }

            long generation = lastGeneration + 1;
            StagedOperation replacement =
                    new StagedOperation(ownerId, generation, deltaCopy, tagCopy);
            staged = replacement;
            lastGeneration = generation;
            return generation;
        }
    }

    /**
     * Invalid arguments throw even when the owner or generation is stale.
     * Empty constraint arrays are allowed.
     */
    public boolean commit(String ownerId, long generation,
                          String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = validatedTags(required, true);
        String[] forbiddenCopy = validatedTags(forbidden, true);

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
                return false;
            }

            for (int i = 0; i < requiredCopy.length; i++) {
                if (!contains(staged.tags, requiredCopy[i])) {
                    return false;
                }
            }
            for (int i = 0; i < forbiddenCopy.length; i++) {
                if (contains(staged.tags, forbiddenCopy[i])) {
                    return false;
                }
            }

            // The staged projection remains valid until this slot is cleared.
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
            long[] committedCopy = copy(committed);
            long[] projectedCopy = copy(committed);

            if (staged == null) {
                return new Snapshot(
                        committedCopy, projectedCopy, null, 0, null, null);
            }

            for (int i = 0; i < projectedCopy.length; i++) {
                projectedCopy[i] += staged.delta[i];
            }

            return new Snapshot(
                    committedCopy,
                    projectedCopy,
                    staged.ownerId,
                    staged.generation,
                    copy(staged.delta),
                    copy(staged.tags));
        }
    }

    private boolean matches(String ownerId, long generation) {
        return staged != null
                && staged.generation == generation
                && staged.ownerId.equals(ownerId);
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Owner ID must be nonempty.");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive.");
        }
    }

    private static long checkedProjection(long quantity, long delta) {
        if (delta < -quantity
                || (delta > 0 && quantity > Long.MAX_VALUE - delta)) {
            throw new IllegalArgumentException(
                    "Projected quantity is outside the permitted range.");
        }
        return quantity + delta;
    }

    private static String[] validatedTags(String[] tags, boolean allowEmpty) {
        if (tags == null || (!allowEmpty && tags.length == 0)) {
            throw new IllegalArgumentException(
                    "Tags must be nonnull and staging tags must be nonempty.");
        }

        String[] result = copy(tags);
        int size = 0;
        for (int i = 0; i < result.length; i++) {
            String tag = result[i];
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException(
                        "Each tag must be nonnull and nonempty.");
            }

            boolean duplicate = false;
            for (int j = 0; j < size; j++) {
                if (result[j].equals(tag)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result[size++] = tag;
            }
        }

        String[] unique = new String[size];
        for (int i = 0; i < size; i++) {
            unique[i] = result[i];
        }
        return unique;
    }

    private static boolean contains(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
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

        private StagedOperation(String ownerId, long generation,
                                long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    /**
     * Immutable, detached snapshot. Array getters return fresh copies.
     * An empty slot has null owner, delta, and tags, and generation zero.
     */
    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final String stagedOwnerId;
        private final long stagedGeneration;
        private final long[] stagedDelta;
        private final String[] stagedTags;

        private Snapshot(long[] committedQuantities,
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

        public long[] getCommittedQuantities() {
            return copy(committedQuantities);
        }

        public long[] getProjectedQuantities() {
            return copy(projectedQuantities);
        }

        public boolean hasStagedOperation() {
            return stagedGeneration != 0;
        }

        public String getStagedOwnerId() {
            return stagedOwnerId;
        }

        public long getStagedGeneration() {
            return stagedGeneration;
        }

        public long[] getStagedDelta() {
            return stagedDelta == null ? null : copy(stagedDelta);
        }

        public String[] getStagedTags() {
            return stagedTags == null ? null : copy(stagedTags);
        }
    }
}
