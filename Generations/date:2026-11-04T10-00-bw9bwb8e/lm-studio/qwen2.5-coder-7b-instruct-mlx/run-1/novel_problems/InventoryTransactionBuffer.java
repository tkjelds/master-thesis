@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private final long[][] deltas;
    private final String[][] tags;
    private final Object lock = new Object();
    private long generation;

    public InventoryTransactionBuffer(long[] committed) {
        if (committed == null || committed.length == 0) {
            throw new IllegalArgumentException("Committed quantities must be nonempty");
        }
        this.committed = committed.clone();
        this.deltas = new long[committed.length][];
        this.tags = new String[committed.length][];
        this.generation = 1;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Invalid input");
        }
        synchronized (lock) {
            long gen = generation++;
            deltas[ownerId.hashCode() % committed.length] = delta.clone();
            this.tags[ownerId.hashCode() % committed.length] = tags.clone();
            return gen;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0 || required == null || forbidden == null) {
            throw new IllegalArgumentException("Invalid input");
        }
        synchronized (lock) {
            if (generation != this.generation || deltas[ownerId.hashCode() % committed.length] == null) {
                return false;
            }
            for (String tag : required) {
                if (!containsTag(tag, tags[ownerId.hashCode() % committed.length])) {
                    return false;
                }
            }
            for (String tag : forbidden) {
                if (containsTag(tag, tags[ownerId.hashCode() % committed.length])) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                long newQuantity = committed[i] + deltas[ownerId.hashCode() % committed.length][i];
                if (newQuantity < 0 || newQuantity > Long.MAX_VALUE) {
                    return false;
                }
            }
            for (int i = 0; i < committed.length; i++) {
                committed[i] += deltas[ownerId.hashCode() % committed.length][i];
            }
            deltas[ownerId.hashCode() % committed.length] = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0) {
            throw new IllegalArgumentException("Invalid input");
        }
        synchronized (lock) {
            if (generation != this.generation || deltas[ownerId.hashCode() % committed.length] == null) {
                return false;
            }
            deltas[ownerId.hashCode() % committed.length] = null;
            return true;
        }
    }

    public long[] snapshot() {
        synchronized (lock) {
            long[] projected = committed.clone();
            for (int i = 0; i < deltas.length; i++) {
                if (deltas[i] != null) {
                    for (int j = 0; j < committed.length; j++) {
                        projected[j] += deltas[i][j];
                    }
                }
            }
            return projected;
        }
    }

    private boolean containsTag(String tag, String[] tags) {
        for (String t : tags) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }
}
