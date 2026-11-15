import java.lang.*;

@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    public static final class Snapshot {
        private final long[] committed;
        private final long[] projected;
        private final boolean staged;
        private final String ownerId;
        private final long generation;
        private final long[] delta;
        private final String[] tags;

        private Snapshot(long[] committed, long[] projected, boolean staged,
                         String ownerId, long generation, long[] delta, String[] tags) {
            this.committed = committed;
            this.projected = projected;
            this.staged = staged;
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }

        public long[] committedQuantities() {
            return committed.clone();
        }

        public long[] projectedQuantities() {
            return projected.clone();
        }

        public boolean hasStaged() {
            return staged;
        }

        /** Null if nothing is staged. */
        public String stagedOwnerId() {
            return ownerId;
        }

        /** Zero if nothing is staged. */
        public long stagedGeneration() {
            return generation;
        }

        /** Empty array if nothing is staged. */
        public long[] stagedDelta() {
            return delta.clone();
        }

        /** Empty array if nothing is staged. */
        public String[] stagedTags() {
            return tags.clone();
        }
    }

    private final Object lock = new Object();
    private final long[] committed;

    private boolean hasSlot = false;
    private String slotOwner = null;
    private long slotGeneration = 0;
    private long[] slotDelta = null;
    private String[] slotTags = null;

    private long lastGeneration = 0;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("initial quantities must be nonempty");
        }
        long[] copy = initialQuantities.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("negative quantity at index " + i);
            }
        }
        this.committed = copy;
    }

    private static void checkOwner(String ownerId) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("ownerId must be non-null and nonempty");
        }
    }

    private static String[] copyTags(String[] tags, String what) {
        if (tags == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        String[] copy = tags.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null || copy[i].isEmpty()) {
                throw new IllegalArgumentException(what + " contains null or empty tag");
            }
        }
        return copy;
    }

    private static boolean contains(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }

    // Must be called with lock held. Returns true if committed + delta is within [0, Long.MAX_VALUE].
    private boolean projectionValid(long[] delta) {
        for (int i = 0; i < committed.length; i++) {
            long c = committed[i];
            long d = delta[i];
            if (d >= 0) {
                if (c > Long.MAX_VALUE - d) {
                    return false;
                }
            } else {
                if (c + d < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        checkOwner(ownerId);
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
        if (delta.length != committed.length) {
            throw new IllegalArgumentException("delta length must equal product count");
        }
        long[] deltaCopy = delta.clone();
        String[] tagsCopy = copyTags(tags, "tags");

        synchronized (lock) {
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter exhausted");
            }
            if (!projectionValid(deltaCopy)) {
                throw new IllegalArgumentException("projected quantity out of range");
            }
            long gen = ++lastGeneration;
            hasSlot = true;
            slotOwner = ownerId;
            slotGeneration = gen;
            slotDelta = deltaCopy;
            slotTags = tagsCopy;
            return gen;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        checkOwner(ownerId);
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        String[] req = copyTags(required, "required");
        String[] forb = copyTags(forbidden, "forbidden");

        synchronized (lock) {
            if (!hasSlot || slotGeneration != generation || !slotOwner.equals(ownerId)) {
                return false;
            }
            for (int i = 0; i < req.length; i++) {
                if (!contains(slotTags, req[i])) {
                    return false;
                }
            }
            for (int i = 0; i < forb.length; i++) {
                if (contains(slotTags, forb[i])) {
                    return false;
                }
            }
            if (!projectionValid(slotDelta)) {
                return false;
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += slotDelta[i];
            }
            clearSlot();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        checkOwner(ownerId);
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        synchronized (lock) {
            if (!hasSlot || slotGeneration != generation || !slotOwner.equals(ownerId)) {
                return false;
            }
            clearSlot();
            return true;
        }
    }

    // Must be called with lock held.
    private void clearSlot() {
        hasSlot = false;
        slotOwner = null;
        slotGeneration = 0;
        slotDelta = null;
        slotTags = null;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] c = committed.clone();
            long[] p = committed.clone();
            if (hasSlot) {
                for (int i = 0; i < p.length; i++) {
                    p[i] += slotDelta[i];
                }
                return new Snapshot(c, p, true, slotOwner, slotGeneration,
                        slotDelta.clone(), slotTags.clone());
            }
            return new Snapshot(c, p, false, null, 0, new long[0], new String[0]);
        }
    }
}
