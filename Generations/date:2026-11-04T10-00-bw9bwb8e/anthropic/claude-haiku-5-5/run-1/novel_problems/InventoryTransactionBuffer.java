@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    /**
     * Immutable, detached view of the buffer state. All arrays are copies.
     */
    public static final class Snapshot {
        private final long[] committed;
        private final long[] projected;
        private final boolean staged;
        private final String owner;
        private final long generation;
        private final String[] tags;

        private Snapshot(long[] committed, long[] projected, boolean staged,
                         String owner, long generation, String[] tags) {
            this.committed = committed;
            this.projected = projected;
            this.staged = staged;
            this.owner = owner;
            this.generation = generation;
            this.tags = tags;
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

        /** Returns the staged owner, or null if nothing is staged. */
        public String getStagedOwner() {
            return owner;
        }

        /** Returns the staged generation, or 0 if nothing is staged. */
        public long getStagedGeneration() {
            return generation;
        }

        /** Returns a copy of the staged tags; empty if nothing is staged. */
        public String[] getStagedTags() {
            return tags.clone();
        }
    }

    // Single monitor guarding every piece of mutable state below.
    private final Object lock = new Object();

    // Committed quantities, indexed by product. Guarded by lock.
    private final long[] committed;

    // Single staging slot. Guarded by lock.
    private boolean occupied;
    private String slotOwner;
    private long slotGeneration;
    private long[] slotDelta;
    private String[] slotTags;

    // Next generation to issue. Guarded by lock. Starts at 1; never reused.
    // Generation Long.MAX_VALUE is never issued; the counter is then exhausted.
    private long nextGeneration = 1L;

    /**
     * Creates a buffer with a copy of the given committed quantities.
     * Policy: all operations are nonblocking. stage replaces any previous
     * staged operation; commit and discard return false when they do not
     * match the current slot. Only built-in monitor synchronization is used.
     */
    public InventoryTransactionBuffer(long[] initialCommitted) {
        if (initialCommitted == null) {
            throw new NullPointerException("initialCommitted must not be null");
        }
        if (initialCommitted.length == 0) {
            throw new IllegalArgumentException("product array must be nonempty");
        }
        for (long q : initialCommitted) {
            if (q < 0) {
                throw new IllegalArgumentException("committed quantities must be nonnegative");
            }
        }
        this.committed = initialCommitted.clone();
        this.occupied = false;
        this.slotOwner = null;
        this.slotGeneration = 0L;
        this.slotDelta = null;
        this.slotTags = new String[0];
    }

    /**
     * Stages a delta, atomically replacing any previously staged operation.
     * Returns a new positive, strictly increasing generation.
     */
    public long stage(String ownerId, long[] delta, String[] tags) {
        checkOwner(ownerId);
        if (delta == null) {
            throw new NullPointerException("delta must not be null");
        }
        if (tags == null) {
            throw new NullPointerException("tags must not be null");
        }
        if (tags.length == 0) {
            throw new IllegalArgumentException("tags must be nonempty");
        }
        for (String t : tags) {
            checkTag(t);
        }

        synchronized (lock) {
            if (delta.length != committed.length) {
                throw new IllegalArgumentException("delta length must match product count");
            }
            // Validate projected quantities before replacing anything.
            for (int i = 0; i < committed.length; i++) {
                long c = committed[i];
                long d = delta[i];
                if (d > 0 && c > Long.MAX_VALUE - d) {
                    throw new IllegalArgumentException("projected quantity exceeds Long.MAX_VALUE at index " + i);
                }
                if (c + d < 0) {
                    throw new IllegalArgumentException("projected quantity below zero at index " + i);
                }
            }

            long gen = issueGenerationLocked();

            // Copy tags as a set (exact, case-sensitive equality).
            String[] uniqueTags = new String[tags.length];
            int count = 0;
            for (String t : tags) {
                if (!containsAt(uniqueTags, count, t)) {
                    uniqueTags[count++] = t;
                }
            }
            String[] storedTags = new String[count];
            System.arraycopy(uniqueTags, 0, storedTags, 0, count);

            occupied = true;
            slotOwner = ownerId;
            slotGeneration = gen;
            slotDelta = delta.clone();
            slotTags = storedTags;
            return gen;
        }
    }

    /**
     * Applies the staged delta if owner and generation match and constraints
     * are satisfied. Returns false without mutation otherwise.
     */
    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        checkOwner(ownerId);
        checkGeneration(generation);
        if (required == null || forbidden == null) {
            throw new NullPointerException("constraint arrays must not be null");
        }
        for (String t : required) {
            checkTag(t);
        }
        for (String t : forbidden) {
            checkTag(t);
        }
        // Copy constraints so caller mutations cannot affect evaluation.
        String[] req = required.clone();
        String[] forb = forbidden.clone();

        synchronized (lock) {
            if (!occupied || generation != slotGeneration || !ownerId.equals(slotOwner)) {
                return false;
            }
            for (String r : req) {
                if (!containsAt(slotTags, slotTags.length, r)) {
                    return false;
                }
            }
            for (String f : forb) {
                if (containsAt(slotTags, slotTags.length, f)) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += slotDelta[i];
            }
            clearSlotLocked();
            return true;
        }
    }

    /**
     * Clears the staging slot only if owner and generation match.
     */
    public boolean discard(String ownerId, long generation) {
        checkOwner(ownerId);
        checkGeneration(generation);
        synchronized (lock) {
            if (!occupied || generation != slotGeneration || !ownerId.equals(slotOwner)) {
                return false;
            }
            clearSlotLocked();
            return true;
        }
    }

    /**
     * Returns a coherent, detached copy of committed quantities, projected
     * quantities, and staging metadata.
     */
    public Snapshot snapshot() {
        synchronized (lock) {
            long[] com = committed.clone();
            long[] proj = committed.clone();
            if (occupied) {
                for (int i = 0; i < proj.length; i++) {
                    proj[i] = committed[i] + slotDelta[i];
                }
                return new Snapshot(com, proj, true, slotOwner, slotGeneration, slotTags.clone());
            }
            return new Snapshot(com, proj, false, null, 0L, new String[0]);
        }
    }

    // ---- helpers (call with lock held where noted) ----

    /** Must be called with lock held. */
    private long issueGenerationLocked() {
        long g = nextGeneration;
        if (g == Long.MAX_VALUE) {
            throw new IllegalStateException("generation counter exhausted");
        }
        nextGeneration = g + 1L;
        return g;
    }

    /** Must be called with lock held. */
    private void clearSlotLocked() {
        occupied = false;
        slotOwner = null;
        slotGeneration = 0L;
        slotDelta = null;
        slotTags = new String[0];
    }

    private static boolean containsAt(String[] arr, int len, String value) {
        for (int i = 0; i < len; i++) {
            if (arr[i].equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void checkOwner(String ownerId) {
        if (ownerId == null) {
            throw new NullPointerException("ownerId must not be null");
        }
        if (ownerId.isEmpty()) {
            throw new IllegalArgumentException("ownerId must not be empty");
        }
    }

    private static void checkGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    private static void checkTag(String tag) {
        if (tag == null) {
            throw new NullPointerException("tag must not be null");
        }
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("tag must not be empty");
        }
    }
}
