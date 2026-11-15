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

        private Snapshot(long[] committed, long[] projected, boolean staged, String ownerId,
                         long generation, long[] delta, String[] tags) {
            this.committed = committed;
            this.projected = projected;
            this.staged = staged;
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }

        public long[] committed() { return committed.clone(); }
        public long[] projected() { return projected.clone(); }
        public boolean hasStaged() { return staged; }
        public String stagedOwner() { return ownerId; }
        public long stagedGeneration() { return generation; }
        public long[] stagedDelta() { return delta.clone(); }
        public String[] stagedTags() { return tags.clone(); }
    }

    private final Object lock = new Object();
    private final long[] committed;

    private boolean hasSlot = false;
    private String slotOwner = null;
    private long slotGeneration = 0L;
    private long[] slotDelta = null;
    private String[] slotTags = null;

    private long lastGeneration = 0L;

    public InventoryTransactionBuffer(long[] initial) {
        if (initial == null || initial.length == 0) {
            throw new IllegalArgumentException("initial must be nonempty");
        }
        long[] copy = initial.clone();
        for (long v : copy) {
            if (v < 0) throw new IllegalArgumentException("negative quantity");
        }
        this.committed = copy;
    }

    private static String[] copyStrings(String[] in, String what) {
        if (in == null) throw new IllegalArgumentException(what + " is null");
        String[] c = in.clone();
        for (String s : c) {
            if (s == null || s.isEmpty()) {
                throw new IllegalArgumentException(what + " contains null or empty entry");
            }
        }
        return c;
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) {
            if (a.equals(s)) return true;
        }
        return false;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("invalid owner");
        }
        if (delta == null) throw new IllegalArgumentException("delta is null");
        long[] d = delta.clone();
        String[] t = copyStrings(tags, "tags");
        synchronized (lock) {
            if (d.length != committed.length) {
                throw new IllegalArgumentException("delta length mismatch");
            }
            for (int i = 0; i < d.length; i++) {
                long c = committed[i];
                long x = d[i];
                if (x < 0) {
                    if (c + x < 0) throw new IllegalArgumentException("projected below zero");
                } else if (x > Long.MAX_VALUE - c) {
                    throw new IllegalArgumentException("projected overflow");
                }
            }
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter exhausted");
            }
            long g = ++lastGeneration;
            hasSlot = true;
            slotOwner = ownerId;
            slotGeneration = g;
            slotDelta = d;
            slotTags = t;
            return g;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("invalid owner");
        }
        if (generation <= 0) throw new IllegalArgumentException("nonpositive generation");
        String[] req = copyStrings(required, "required");
        String[] forb = copyStrings(forbidden, "forbidden");
        synchronized (lock) {
            if (!hasSlot || slotGeneration != generation || !slotOwner.equals(ownerId)) {
                return false;
            }
            for (String r : req) {
                if (!contains(slotTags, r)) return false;
            }
            for (String f : forb) {
                if (contains(slotTags, f)) return false;
            }
            // verify entire delta before mutating
            for (int i = 0; i < committed.length; i++) {
                long c = committed[i];
                long x = slotDelta[i];
                if (x < 0) {
                    if (c + x < 0) return false;
                } else if (x > Long.MAX_VALUE - c) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += slotDelta[i];
            }
            clearSlot();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("invalid owner");
        }
        if (generation <= 0) throw new IllegalArgumentException("nonpositive generation");
        synchronized (lock) {
            if (hasSlot && slotGeneration == generation && slotOwner.equals(ownerId)) {
                clearSlot();
                return true;
            }
            return false;
        }
    }

    private void clearSlot() {
        hasSlot = false;
        slotOwner = null;
        slotGeneration = 0L;
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
            return new Snapshot(c, p, false, null, 0L, new long[0], new String[0]);
        }
    }
}
