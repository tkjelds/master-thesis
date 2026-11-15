```java
public class InventoryTransactionBuffer {
    private final long[] committed;
    private final String[][] tags;
    private static long generation = 0;

    public InventoryTransactionBuffer(long[] committed, String[] tags) {
        if (committed == null || tags == null || committed.length != tags.length || committed.length == 0) {
            throw new IllegalArgumentException("Invalid input");
        }
        this.committed = committed.clone();
        this.tags = tags.clone();
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        synchronized (this) {
            if (ownerId == null || delta == null || tags == null || ownerId.isEmpty() || delta.length != this.committed.length || tags.length != this.tags.length) {
                throw new IllegalArgumentException("Invalid input");
            }
            for (int i = 0; i < delta.length; i++) {
                if (delta[i] < 0 || delta[i] > Long.MAX_VALUE) {
                    throw new IllegalArgumentException("Invalid input");
                }
            }

            long currentGen = ++generation;
            this.tags = tags.clone();
            return currentGen;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        synchronized (this) {
            if (ownerId == null || required == null || forbidden == null || ownerId.isEmpty()) {
                throw new IllegalArgumentException("Invalid input");
            }
            if (generation <= 0 || generation < this.generation) {
                return false;
            }

            boolean requiredValid = true;
            for (String tag : required) {
                if (!containsTag(this.tags, tag)) {
                    return false;
                }
            }

            boolean forbiddenValid = true;
            for (String tag : forbidden) {
                if (containsTag(this.tags, tag)) {
                    return false;
                }
            }

            applyDelta(this.committed, delta);
            this.tags = tags.clone();
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        synchronized (this) {
            if (ownerId == null || generation <= 0 || generation < this.generation) {
                return false;
            }
            this.tags = tags.clone();
            return true;
        }
    }

    public long[] snapshot() {
        synchronized (this) {
            return committed.clone();
        }
    }

    private boolean containsTag(String[] tags, String tag) {
        for (String t : tags) {
            if (t.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private void applyDelta(long[] committed, long[] delta) {
        for (int i = 0; i < committed.length; i++) {
            committed[i] += delta[i];
        }
    }
}
```
