@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private long generation = 0;
    private String currentOwnerId = null;
    private long[] delta;
    private String[] tags;

    public InventoryTransactionBuffer(long[] committed) {
        if (committed == null || committed.length == 0) {
            throw new IllegalArgumentException("Committed array must be nonempty");
        }
        this.committed = committed.clone();
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Owner ID, delta, and tags must not be null");
        }
        if (delta.length != committed.length || tags.length == 0) {
            throw new IllegalArgumentException("Invalid array lengths");
        }
        for (long d : delta) {
            if (d < 0 || d > Long.MAX_VALUE) {
                throw new IllegalArgumentException("Projected quantity out of bounds");
            }
        }

        synchronized (this) {
            if (currentOwnerId != null && !currentOwnerId.equals(ownerId)) {
                throw new IllegalStateException("Owner mismatch");
            }
            this.delta = delta.clone();
            this.tags = tags.clone();
            currentOwnerId = ownerId;
            generation++;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || generation < 1) {
            throw new IllegalArgumentException("Owner ID must not be null and generation must be positive");
        }
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException("Required and forbidden arrays must not be null");
        }

        synchronized (this) {
            if (!currentOwnerId.equals(ownerId) || generation != this.generation) {
                return false;
            }
            for (String tag : required) {
                if (!containsTag(tags, tag)) {
                    return false;
                }
            }
            for (String tag : forbidden) {
                if (containsTag(tags, tag)) {
                    return false;
                }
            }

            for (int i = 0; i < committed.length; i++) {
                long newQuantity = committed[i] + delta[i];
                if (newQuantity < 0 || newQuantity > Long.MAX_VALUE) {
                    throw new IllegalStateException("Committed quantity out of bounds");
                }
                committed[i] = newQuantity;
            }

            currentOwnerId = null;
            this.delta = null;
            this.tags = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty() || generation < 1) {
            throw new IllegalArgumentException("Owner ID must not be null and generation must be positive");
        }

        synchronized (this) {
            if (!currentOwnerId.equals(ownerId) || generation != this.generation) {
                return false;
            }
            currentOwnerId = null;
            this.delta = null;
            this.tags = null;
            return true;
        }
    }

    public long[] snapshot() {
        if (generation < 1) {
            throw new IllegalArgumentException("Generation must be positive");
        }

        synchronized (this) {
            return committed.clone();
        }
    }

    private static boolean containsTag(String[] tags, String tag) {
        for (String t : tags) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }
}
