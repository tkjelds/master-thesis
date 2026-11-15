@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final long[] committed;

    // Single staging slot, guarded by lock. stagedOwner == null means the slot is empty.
    private String stagedOwner;
    private long stagedGeneration;
    private long[] stagedDelta;
    private String[] stagedTags;

    // Next generation to hand out. Generations start at 1 and are never reused.
    private long nextGeneration = 1L;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null) {
            throw new NullPointerException("initialQuantities must not be null");
        }
        if (initialQuantities.length == 0) {
            throw new IllegalArgumentException("initialQuantities must not be empty");
        }
        for (int i = 0; i < initialQuantities.length; i++) {
            if (initialQuantities[i] < 0L) {
                throw new IllegalArgumentException("committed quantity must be nonnegative at index " + i);
            }
        }
        this.committed = initialQuantities.clone();
    }

    /**
     * Replaces any previously staged operation with a new one.
     * Returns a unique, positive, monotonically increasing generation.
     * Throws if the generation counter is exhausted (no replacement occurs).
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        checkOwnerNonNullNonEmpty(ownerId);
        if (delta == null) {
            throw new NullPointerException("delta must not be null");
        }
        if (tags == null) {
            throw new NullPointerException("tags must not be null");
        }
        if (tags.length == 0) {
            throw new IllegalArgumentException("tags must not be empty");
        }
        if (delta.length != committed.length) {
            throw new IllegalArgumentException("delta length must equal product count");
        }
        long[] deltaCopy = delta.clone();
        String[] tagsCopy = tags.clone();
        for (int i = 0; i < tagsCopy.length; i++) {
            checkTagValid(tagsCopy[i], "tag");
        }

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                long base = committed[i];
                long d = deltaCopy[i];
                if (d >= 0L) {
                    if (base > Long.MAX_VALUE - d) {
                        throw new IllegalArgumentException("projected quantity overflows at index " + i);
                    }
                } else {
                    if (base + d < 0L) {
                        throw new IllegalArgumentException("projected quantity below zero at index " + i);
                    }
                }
            }
            if (nextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter exhausted");
            }
            long gen = nextGeneration;
            nextGeneration++;

            stagedOwner = ownerId;
            stagedGeneration = gen;
            stagedDelta = deltaCopy;
            stagedTags = tagsCopy;
            return gen;
        }
    }

    /**
     * Applies the staged delta to committed quantities if the owner and generation match
     * and the tag constraints are satisfied. Returns false without mutation otherwise.
     */
    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        checkOwnerNonNullNonEmpty(ownerId);
        checkGenerationPositive(generation);
        if (required == null) {
            throw new IllegalArgumentException("required must not be null");
        }
        if (forbidden == null) {
            throw new IllegalArgumentException("forbidden must not be null");
        }
        String[] req = required.clone();
        String[] forb = forbidden.clone();
        for (int i = 0; i < req.length; i++) {
            checkTagValid(req[i], "required tag");
        }
        for (int i = 0; i < forb.length; i++) {
            checkTagValid(forb[i], "forbidden tag");
        }

        synchronized (lock) {
            if (stagedOwner == null || stagedGeneration != generation || !stagedOwner.equals(ownerId)) {
                return false;
            }
            for (int i = 0; i < req.length; i++) {
                if (!containsTag(stagedTags, req[i])) {
                    return false;
                }
            }
            for (int i = 0; i < forb.length; i++) {
                if (containsTag(stagedTags, forb[i])) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += stagedDelta[i];
            }
            clearStagedLocked();
            return true;
        }
    }

    /**
     * Clears the staging slot only if owner and generation match.
     */
    public boolean discard(String ownerId, long generation) {
        checkOwnerNonNullNonEmpty(ownerId);
        checkGenerationPositive(generation);
        synchronized (lock) {
            if (stagedOwner == null || stagedGeneration != generation || !stagedOwner.equals(ownerId)) {
                return false;
            }
            clearStagedLocked();
            return true;
        }
    }

    /**
     * Returns a coherent, fully detached view of committed, projected, and staging state.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = committed.clone();
            long[] projectedCopy = committed.clone();
            boolean staged = stagedOwner != null;
            if (staged) {
                for (int i = 0; i < projectedCopy.length; i++) {
                    projectedCopy[i] = committed[i] + stagedDelta[i];
                }
                return new Snapshot(committedCopy, projectedCopy, true,
                        stagedOwner, stagedGeneration, stagedTags.clone());
            }
            return new Snapshot(committedCopy, projectedCopy, false,
                    null, 0L, new String[0]);
        }
    }

    // ---- helpers ----

    private void clearStagedLocked() {
        stagedOwner = null;
        stagedGeneration = 0L;
        stagedDelta = null;
        stagedTags = null;
    }

    private static void checkOwnerNonNullNonEmpty(String ownerId) {
        if (ownerId == null) {
            throw new NullPointerException("ownerId must not be null");
        }
        if (ownerId.isEmpty()) {
            throw new IllegalArgumentException("ownerId must not be empty");
        }
    }

    private static void checkGenerationPositive(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    private static void checkTagValid(String tag, String what) {
        if (tag == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        if (tag.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
    }

    private static boolean containsTag(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Immutable snapshot with defensive copies on every access.
     */
    public static final class Snapshot {
        private final long[] committed;
        private final long[] projected;
        private final boolean staged;
        private final String stagedOwner;
        private final long stagedGeneration;
        private final String[] stagedTags;

        private Snapshot(long[] committed, long[] projected, boolean staged,
                         String stagedOwner, long stagedGeneration, String[] stagedTags) {
            this.committed = committed;
            this.projected = projected;
            this.staged = staged;
            this.stagedOwner = stagedOwner;
            this.stagedGeneration = stagedGeneration;
            this.stagedTags = stagedTags;
        }

        public long[] getCommitted() {
            return committed.clone();
        }

        public long[] getProjected() {
            return projected.clone();
        }

        public boolean isStaged() {
            return staged;
        }

        public String getStagedOwner() {
            return stagedOwner;
        }

        public long getStagedGeneration() {
            return stagedGeneration;
        }

        public String[] getStagedTags() {
            return stagedTags.clone();
        }
    }
}
