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
        private final long lastIssuedGeneration;

        private Snapshot(long[] committed, long[] projected, boolean staged, String ownerId,
                         long generation, long[] delta, String[] tags, long lastIssuedGeneration) {
            this.committed = committed;
            this.projected = projected;
            this.staged = staged;
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
            this.lastIssuedGeneration = lastIssuedGeneration;
        }

        public long[] committed() { return committed.clone(); }
        public long[] projected() { return projected.clone(); }
        public boolean hasStaged() { return staged; }
        public String stagedOwner() { return ownerId; }
        public long stagedGeneration() { return generation; }
        public long[] stagedDelta() { return delta.clone(); }
        public String[] stagedTags() { return tags.clone(); }
        public long lastIssuedGeneration() { return lastIssuedGeneration; }
    }

    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration = 0L;

    private boolean hasStaged = false;
    private String stagedOwner = null;
    private long stagedGeneration = 0L;
    private long[] stagedDelta = null;
    private String[] stagedTags = null;

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null) {
            throw new IllegalArgumentException("initialQuantities is null");
        }
        if (initialQuantities.length == 0) {
            throw new IllegalArgumentException("initialQuantities is empty");
        }
        long[] copy = initialQuantities.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("negative quantity at " + i);
            }
        }
        this.committed = copy;
    }

    private static boolean contains(String[] arr, int count, String s) {
        for (int i = 0; i < count; i++) {
            if (arr[i].equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static String[] copyTags(String[] tags, String what) {
        if (tags == null) {
            throw new IllegalArgumentException(what + " is null");
        }
        String[] src = tags.clone();
        String[] tmp = new String[src.length];
        int n = 0;
        for (int i = 0; i < src.length; i++) {
            String t = src[i];
            if (t == null || t.isEmpty()) {
                throw new IllegalArgumentException(what + " contains null or empty entry");
            }
            if (!contains(tmp, n, t)) {
                tmp[n++] = t;
            }
        }
        String[] res = new String[n];
        System.arraycopy(tmp, 0, res, 0, n);
        return res;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("invalid ownerId");
        }
        if (delta == null) {
            throw new IllegalArgumentException("delta is null");
        }
        if (tags == null) {
            throw new IllegalArgumentException("tags is null");
        }
        long[] deltaCopy = delta.clone();
        String[] tagCopy = copyTags(tags, "tags");
        if (tagCopy.length == 0) {
            throw new IllegalArgumentException("tags is empty");
        }
        synchronized (lock) {
            if (deltaCopy.length != committed.length) {
                throw new IllegalArgumentException("delta length mismatch");
            }
            for (int i = 0; i < deltaCopy.length; i++) {
                long d = deltaCopy[i];
                long c = committed[i];
                if (d < 0) {
                    if (c + d < 0) {
                        throw new IllegalArgumentException("projected quantity below zero at " + i);
                    }
                } else if (d > 0) {
                    if (c > Long.MAX_VALUE - d) {
                        throw new IllegalArgumentException("projected quantity overflow at " + i);
                    }
                }
            }
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter exhausted");
            }
            long gen = ++lastGeneration;
            hasStaged = true;
            stagedOwner = ownerId;
            stagedGeneration = gen;
            stagedDelta = deltaCopy;
            stagedTags = tagCopy;
            return gen;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("invalid ownerId");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        String[] req = copyTags(required, "required");
        String[] forb = copyTags(forbidden, "forbidden");
        synchronized (lock) {
            if (!hasStaged || stagedGeneration != generation || !stagedOwner.equals(ownerId)) {
                return false;
            }
            for (int i = 0; i < req.length; i++) {
                if (!contains(stagedTags, stagedTags.length, req[i])) {
                    return false;
                }
            }
            for (int i = 0; i < forb.length; i++) {
                if (contains(stagedTags, stagedTags.length, forb[i])) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += stagedDelta[i];
            }
            clearStaged();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("invalid ownerId");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        synchronized (lock) {
            if (!hasStaged || stagedGeneration != generation || !stagedOwner.equals(ownerId)) {
                return false;
            }
            clearStaged();
            return true;
        }
    }

    private void clearStaged() {
        hasStaged = false;
        stagedOwner = null;
        stagedGeneration = 0L;
        stagedDelta = null;
        stagedTags = null;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] c = committed.clone();
            long[] p = committed.clone();
            if (hasStaged) {
                for (int i = 0; i < p.length; i++) {
                    p[i] += stagedDelta[i];
                }
                return new Snapshot(c, p, true, stagedOwner, stagedGeneration,
                        stagedDelta.clone(), stagedTags.clone(), lastGeneration);
            }
            return new Snapshot(c, p, false, null, 0L,
                    new long[committed.length], new String[0], lastGeneration);
        }
    }
}
