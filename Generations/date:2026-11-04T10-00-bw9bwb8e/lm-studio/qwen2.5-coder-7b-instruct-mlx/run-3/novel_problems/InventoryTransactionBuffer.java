@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committedQuantities;
    private final Object stagingLock = new Object();
    private OwnerStagingSlot currentStage;
    private long nextGeneration;

    public InventoryTransactionBuffer(long[] committedQuantities) {
        if (committedQuantities == null || committedQuantities.length == 0) {
            throw new IllegalArgumentException("Nonempty long[] of nonnegative committed quantities is required");
        }
        this.committedQuantities = committedQuantities.clone();
        this.currentStage = new OwnerStagingSlot("", new long[committedQuantities.length], new String[0]);
        this.nextGeneration = 1;
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Non-null owner ID and non-null/empty tags are required");
        }
        if (delta.length != committedQuantities.length) {
            throw new IllegalArgumentException("Delta length must match array lengths");
        }
        synchronized (stagingLock) {
            long[] stagedDelta = new long[delta.length];
            for (int i = 0; i < delta.length; i++) {
                if (delta[i] < -Long.MAX_VALUE || delta[i] > Long.MAX_VALUE) {
                    throw new IllegalArgumentException("Invalid projected quantity");
                }
                stagedDelta[i] = delta[i];
            }
            currentStage = new OwnerStagingSlot(ownerId, stagedDelta, tags);
            return nextGeneration++;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0) {
            throw new IllegalArgumentException("Non-empty owner ID and positive generation are required");
        }
        if (required == null || forbidden == null) {
            throw new IllegalArgumentException("Non-null constraint arrays are required");
        }
        synchronized (stagingLock) {
            if (currentStage.ownerId.equals(ownerId) && currentStage.generation == generation) {
                for (String tag : required) {
                    if (!containsTag(currentStage.tags, tag)) {
                        return false;
                    }
                }
                for (String tag : forbidden) {
                    if (containsTag(currentStage.tags, tag)) {
                        return false;
                    }
                }
                for (int i = 0; i < committedQuantities.length; i++) {
                    long newQuantity = Math.min(
                        Long.MAX_VALUE,
                        Math.max(0, committedQuantities[i] + currentStage.delta[i])
                    );
                    committedQuantities[i] = newQuantity;
                }
                currentStage = new OwnerStagingSlot("", new long[committedQuantities.length], new String[0]);
                return true;
            }
            return false;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0) {
            throw new IllegalArgumentException("Non-empty owner ID and positive generation are required");
        }
        synchronized (stagingLock) {
            if (currentStage.ownerId.equals(ownerId) && currentStage.generation == generation) {
                currentStage = new OwnerStagingSlot("", new long[committedQuantities.length], new String[0]);
                return true;
            }
            return false;
        }
    }

    public Snapshot snapshot(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Positive generation is required");
        }
        synchronized (stagingLock) {
            long[] projectedQuantities = committedQuantities.clone();
            if (!currentStage.ownerId.isEmpty()) {
                for (int i = 0; i < projectedQuantities.length; i++) {
                    projectedQuantities[i] += currentStage.delta[i];
                }
            }
            return new Snapshot(currentStage.ownerId, generation, currentStage.tags, committedQuantities, projectedQuantities);
        }
    }

    private boolean containsTag(String[] tags, String tag) {
        for (String existingTag : tags) {
            if (existingTag.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static class OwnerStagingSlot {
        final String ownerId;
        final long[] delta;
        final String[] tags;

        OwnerStagingSlot(String ownerId, long[] delta, String[] tags) {
            this.ownerId = ownerId;
            this.delta = delta.clone();
            this.tags = tags.clone();
        }
    }

    public static class Snapshot {
        final String ownerId;
        final long generation;
        final String[] tags;
        private final long[] committedQuantities;
        private final long[] projectedQuantities;

        Snapshot(String ownerId, long generation, String[] tags, long[] committedQuantities, long[] projectedQuantities) {
            this.ownerId = ownerId;
            this.generation = generation;
            this.tags = tags.clone();
            this.committedQuantities = committedQuantities.clone();
            this.projectedQuantities = projectedQuantities.clone();
        }

        public long[] getCommittedQuantities() {
            return committedQuantities;
        }

        public long[] getProjectedQuantities() {
            return projectedQuantities;
        }
    }
}
