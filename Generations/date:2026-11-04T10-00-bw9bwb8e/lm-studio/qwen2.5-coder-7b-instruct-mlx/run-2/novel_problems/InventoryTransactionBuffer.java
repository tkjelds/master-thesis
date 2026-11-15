@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private final Object lock = new Object();
    private int generation = 0;
    private long[] stagedDelta;
    private String[] stagedTags;

    public InventoryTransactionBuffer(long[] committed) {
        if (committed == null || committed.length == 0) {
            throw new IllegalArgumentException("Committed array must be nonempty and nonnull");
        }
        this.committed = committed.clone();
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Owner ID, delta, and tags must be nonnull and nonempty");
        }
        if (delta.length != committed.length || tags.length == 0) {
            throw new IllegalArgumentException("Delta and tags must have the same length");
        }

        for (int i = 0; i < delta.length; i++) {
            if (delta[i] < Long.MIN_VALUE || delta[i] > Long.MAX_VALUE) {
                throw new IllegalArgumentException("Delta values must be within the range of long");
            }
        }

        synchronized (lock) {
            stagedDelta = delta.clone();
            stagedTags = tags.clone();
            generation++;
        }

        return generation;
    }

    public boolean commit(String ownerId, long expectedGeneration, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || expectedGeneration <= 0) {
            throw new IllegalArgumentException("Owner ID and generation must be nonnull and positive");
        }
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException("Required and forbidden arrays must be nonnull");
        }

        synchronized (lock) {
            if (generation != expectedGeneration) {
                return false;
            }

            for (String tag : required) {
                if (!containsTag(stagedTags, tag)) {
                    return false;
                }
            }

            for (String tag : forbidden) {
                if (containsTag(stagedTags, tag)) {
                    return false;
                }
            }

            for (int i = 0; i < committed.length; i++) {
                long newQuantity = committed[i] + stagedDelta[i];
                if (newQuantity < 0) {
                    return false;
                }
                committed[i] = newQuantity;
            }

            stagedDelta = null;
            stagedTags = null;

            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0) {
            throw new IllegalArgumentException("Owner ID and generation must be nonnull and positive");
        }

        synchronized (lock) {
            if (generation == expectedGeneration) {
                stagedDelta = null;
                stagedTags = null;
                return true;
            }

            return false;
        }
    }

    public long[] snapshot() {
        long[] projected = committed.clone();
        if (stagedDelta != null) {
            for (int i = 0; i < projected.length; i++) {
                projected[i] += stagedDelta[i];
            }
        }

        return projected;
    }

    private boolean containsTag(String[] tags, String tag) {
        for (String t : tags) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }
}
