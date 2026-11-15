@InventoryTransactionBuffer.ThreadSafe
public final class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final long[] committed;

    // Accessed only while holding lock.
    private long lastGeneration;
    private Slot slot;

    private static final class Slot {
        final String ownerId;
        final long generation;
        final long[] delta;
        final String[] tags;

        Slot(String ownerId, long generation, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    /**
     * All arrays in a snapshot are detached from the buffer.
     * When there is no staged operation, ownerId, delta, and tags are null,
     * and generation is zero.
     */
    public static final class Snapshot {
        public final long[] committed;
        public final long[] projected;
        public final String ownerId;
        public final long generation;
        public final long[] delta;
        public final String[] tags;

        private Snapshot(long[] committed, long[] projected, String ownerId,
                         long generation, long[] delta, String[] tags) {
            this.committed = committed;
            this.projected = projected;
            this.ownerId = ownerId;
            this.generation = generation;
            this.delta = delta;
            this.tags = tags;
        }
    }

    public InventoryTransactionBuffer(long[] initialQuantities) {
        if (initialQuantities == null || initialQuantities.length == 0) {
            throw new IllegalArgumentException("Quantities must be nonempty");
        }
        long[] copy = initialQuantities.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("Negative quantity");
            }
        }
        committed = copy;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        validateOwner(ownerId);
        if (delta == null || delta.length != committed.length) {
            throw new IllegalArgumentException("Invalid delta");
        }

        long[] deltaCopy = delta.clone();
        String[] tagCopy = copyTagSet(tags);

        synchronized (lock) {
            for (int i = 0; i < committed.length; i++) {
                validateProjection(committed[i], deltaCopy[i]);
            }
            if (lastGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation counter exhausted");
            }

            long generation = lastGeneration + 1;
            Slot replacement = new Slot(ownerId, generation, deltaCopy, tagCopy);
            slot = replacement;
            lastGeneration = generation;
            return generation;
        }
    }

    public boolean commit(String ownerId, long generation,
                          String[] required, String[] forbidden) {
        validateOwner(ownerId);
        validateGeneration(generation);
        String[] requiredCopy = copyTagSet(required);
        String[] forbiddenCopy = copyTagSet(forbidden);

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
                return false;
            }

            for (int i = 0; i < requiredCopy.length; i++) {
                if (!contains(slot.tags, requiredCopy[i])) {
                    return false;
                }
            }
            for (int i = 0; i < forbiddenCopy.length; i++) {
                if (contains(slot.tags, forbiddenCopy[i])) {
                    return false;
                }
            }

            for (int i = 0; i < committed.length; i++) {
                committed[i] += slot.delta[i];
            }
            slot = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        validateOwner(ownerId);
        validateGeneration(generation);

        synchronized (lock) {
            if (!matches(ownerId, generation)) {
                return false;
            }
            slot = null;
            return true;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long[] committedCopy = committed.clone();
            long[] projectedCopy = committed.clone();

            if (slot == null) {
                return new Snapshot(committedCopy, projectedCopy, null, 0,
                                    null, null);
            }

            for (int i = 0; i < projectedCopy.length; i++) {
                projectedCopy[i] += slot.delta[i];
            }
            return new Snapshot(committedCopy, projectedCopy, slot.ownerId,
                                slot.generation, slot.delta.clone(),
                                slot.tags.clone());
        }
    }

    // Called only while holding lock.
    private boolean matches(String ownerId, long generation) {
        return slot != null
                && slot.generation == generation
                && slot.ownerId.equals(ownerId);
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.length() == 0) {
            throw new IllegalArgumentException("Invalid owner ID");
        }
    }

    private static void validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Generation must be positive");
        }
    }

    private static void validateProjection(long quantity, long delta) {
        if (delta > 0 && quantity > Long.MAX_VALUE - delta) {
            throw new IllegalArgumentException("Projected quantity overflows");
        }
        if (delta < 0 && delta < -quantity) {
            throw new IllegalArgumentException("Projected quantity is negative");
        }
    }

    private static String[] copyTagSet(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags must not be null");
        }

        String[] unique = new String[tags.length];
        int count = 0;
        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i];
            if (tag == null || tag.length() == 0) {
                throw new IllegalArgumentException("Invalid tag");
            }

            boolean found = false;
            for (int j = 0; j < count; j++) {
                if (unique[j].equals(tag)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                unique[count++] = tag;
            }
        }

        if (count == unique.length) {
            return unique;
        }
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = unique[i];
        }
        return result;
    }

    private static boolean contains(String[] tags, String tag) {
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                return true;
            }
        }
        return false;
    }
}
