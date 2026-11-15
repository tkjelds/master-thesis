import java.lang.reflect.Array;

@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final long[] committed;
    private long lastGeneration;

    private boolean staged;
    private String stagedOwner;
    private long stagedGeneration;
    private long[] stagedDelta;
    private String[] stagedTags;

    public InventoryTransactionBuffer(long[] initialCommitted) {
        if (initialCommitted == null) {
            throw new NullPointerException("initialCommitted");
        }
        if (initialCommitted.length == 0) {
            throw new IllegalArgumentException("initialCommitted must be nonempty");
        }
        for (long q : initialCommitted) {
            if (q < 0) {
                throw new IllegalArgumentException("committed quantities must be nonnegative");
            }
        }
        this.committed = initialCommitted.clone();
        this.lastGeneration = 0L;
        this.staged = false;
        this.stagedOwner = null;
        this.stagedGeneration = 0L;
        this.stagedDelta = null;
        this.stagedTags = null;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        checkOwner(ownerId);
        if (delta == null) {
            throw new NullPointerException("delta");
        }
        if (delta.length != committed.length) {
            throw new IllegalArgumentException("delta length must equal product count");
        }
        if (tags == null) {
            throw new NullPointerException("tags");
        }
        if (tags.length == 0) {
            throw new IllegalArgumentException("tags must be nonempty");
        }
        long[] deltaCopy = delta.clone();
        String[] tagsCopy = normalizeTags(tags, false);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                long c = committed[i];
                long d = deltaCopy[i];
                if (d < 0) {
                    if (d < -c) {
                        throw new IllegalArgumentException("projected quantity below zero at product " + i);
                    }
                } else {
                    if (c > Long.MAX_VALUE - d) {
                        throw new IllegalArgumentException("projected quantity above Long.MAX_VALUE at product " + i);
                    }
                }
            }
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("generation counter exhausted");
            }
            lastGeneration++;
            staged = true;
            stagedOwner = ownerId;
            stagedGeneration = lastGeneration;
            stagedDelta = deltaCopy;
            stagedTags = tagsCopy;
            return lastGeneration;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        checkOwner(ownerId);
        checkGeneration(generation);
        String[] req = normalizeTags(required, true);
        String[] forb = normalizeTags(forbidden, true);

        synchronized (lock) {
            if (!staged || generation != stagedGeneration || !stagedOwner.equals(ownerId)) {
                return false;
            }
            for (String r : req) {
                if (!contains(stagedTags, r)) {
                    return false;
                }
            }
            for (String f : forb) {
                if (contains(stagedTags, f)) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += stagedDelta[i];
            }
            clearStaging();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        checkOwner(ownerId);
        checkGeneration(generation);
        synchronized (lock) {
            if (!staged || generation != stagedGeneration || !stagedOwner.equals(ownerId)) {
                return false;
            }
            clearStaging();
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            int n = committed.length;
            long[] committedCopy = committed.clone();
            long[] projected = committed.clone();
            long[] deltaCopy = new long[n];
            String[] tagsCopy = new String[0];
            String owner = null;
            long gen = 0L;
            if (staged) {
                for (int i = 0; i < n; i++) {
                    projected[i] = committed[i] + stagedDelta[i];
                    deltaCopy[i] = stagedDelta[i];
                }
                tagsCopy = stagedTags.clone();
                owner = stagedOwner;
                gen = stagedGeneration;
            }
            return new Snapshot(committedCopy, projected, deltaCopy, tagsCopy, owner, gen, staged);
        }
    }

    private void clearStaging() {
        staged = false;
        stagedOwner = null;
        stagedGeneration = 0L;
        stagedDelta = null;
        stagedTags = null;
    }

    private static void checkOwner(String ownerId) {
        if (ownerId == null) {
            throw new NullPointerException("ownerId");
        }
        if (ownerId.isEmpty()) {
            throw new IllegalArgumentException("ownerId must be nonempty");
        }
    }

    private static void checkGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    // Returns a deduplicated (set-semantics) detached copy of the tags.
    private static String[] normalizeTags(String[] src, boolean allowEmpty) {
        if (src == null) {
            throw new IllegalArgumentException("tag array must not be null");
        }
        if (!allowEmpty && src.length == 0) {
            throw new IllegalArgumentException("tags must be nonempty");
        }
        String[] out = new String[src.length];
        int count = 0;
        for (String s : src) {
            if (s == null) {
                throw new IllegalArgumentException("tag must not be null");
            }
            if (s.isEmpty()) {
                throw new IllegalArgumentException("tag must not be empty");
            }
            boolean dup = false;
            for (int j = 0; j < count; j++) {
                if (out[j].equals(s)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out[count++] = s;
            }
        }
        String[] result = new String[count];
        System.arraycopy(out, 0, result, 0, count);
        return result;
    }

    private static boolean contains(String[] arr, String value) {
        for (String s : arr) {
            if (s.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static final class Snapshot {
        private final long[] committedQuantities;
        private final long[] projectedQuantities;
        private final long[] stagedDelta;
        private final String[] stagedTags;
        private final String stagedOwnerId;
        private final long stagedGeneration;
        private final boolean hasStagedOperation;

        private Snapshot(long[] committedQuantities, long[] projectedQuantities, long[] stagedDelta,
                         String[] stagedTags, String stagedOwnerId, long stagedGeneration,
                         boolean hasStagedOperation) {
            this.committedQuantities = committedQuantities;
            this.projectedQuantities = projectedQuantities;
            this.stagedDelta = stagedDelta;
            this.stagedTags = stagedTags;
            this.stagedOwnerId = stagedOwnerId;
            this.stagedGeneration = stagedGeneration;
            this.hasStagedOperation = hasStagedOperation;
        }

        public long[] getCommittedQuantities() {
            return committedQuantities.clone();
        }

        public long[] getProjectedQuantities() {
            return projectedQuantities.clone();
        }

        public long[] getStagedDelta() {
            return stagedDelta.clone();
        }

        public String[] getStagedTags() {
            return stagedTags.clone();
        }

        public String getStagedOwnerId() {
            return stagedOwnerId;
        }

        public long getStagedGeneration() {
            return stagedGeneration;
        }

        public boolean hasStagedOperation() {
            return hasStagedOperation;
        }
    }
}
