@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committedQuantities;
    private final StagingSlot stagingSlot;

    public InventoryTransactionBuffer(long[] initialCommitment) {
        if (initialCommitment == null || initialCommitment.length == 0) {
            throw new IllegalArgumentException("Initial commitment must be a non-empty array of nonnegative values.");
        }
        this.committedQuantities = new long[initialCommitment.length];
        System.arraycopy(initialCommitment, 0, this.committedQuantities, 0, initialCommitment.length);
        this.stagingSlot = new StagingSlot();
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Owner ID and both delta and tags must not be null.");
        }
        if (delta.length != committedQuantities.length || tags.length == 0) {
            throw new IllegalArgumentException("Delta must have the same length as committed quantities and tags must be non-empty.");
        }
        for (long q : delta) {
            if (q < 0L || q > Long.MAX_VALUE) {
                throw new IllegalArgumentException("Quantities must be nonnegative and below Long.MAX_VALUE.");
            }
        }

        return stagingSlot.stage(ownerId, delta, tags);
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || required == null || forbidden == null) {
            throw new IllegalArgumentException("Owner ID and both required and forbidden tags must not be null.");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("Generation must be positive.");
        }

        return stagingSlot.commit(ownerId, generation, required, forbidden);
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty() || generation <= 0L) {
            throw new IllegalArgumentException("Owner ID must not be null and generation must be positive.");
        }

        return stagingSlot.discard(ownerId, generation);
    }

    public long[] snapshot() {
        synchronized (committedQuantities) {
            long[] snapshot = new long[committedQuantities.length];
            System.arraycopy(committedQuantities, 0, snapshot, 0, committedQuantities.length);
            return snapshot;
        }
    }

    private static class StagingSlot {
        private final Object lock = new Object();
        private OwnerData currentOwnerData;
        private long generationCounter;

        public StagingSlot() {
            this.currentOwnerData = null;
            this.generationCounter = 1L; // Nonzero starting generation
        }

        public long stage(String ownerId, long[] delta, String[] tags) {
            synchronized (lock) {
                if (currentOwnerData != null && !ownerId.equals(currentOwnerData.ownerId)) {
                    throw new IllegalStateException("Cannot stage a new operation while another is staged by a different owner.");
                }

                long newGeneration = generationCounter++;
                currentOwnerData = new OwnerData(ownerId, delta.clone(), tags);
                return newGeneration;
            }
        }

        public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
            synchronized (lock) {
                if (currentOwnerData == null || !ownerId.equals(currentOwnerData.ownerId)) {
                    return false;
                }

                if (generation != currentOwnerData.generation) {
                    return false;
                }

                for (String tag : required) {
                    if (!currentOwnerData.tagsSet.contains(tag)) {
                        return false;
                    }
                }

                for (String tag : forbidden) {
                    if (currentOwnerData.tagsSet.contains(tag)) {
                        return false;
                    }
                }

                for (int i = 0; i < committedQuantities.length; i++) {
                    long originalQuantity = committedQuantities[i];
                    long newQuantity = currentOwnerData.delta[i] + originalQuantity;
                    if (newQuantity < 0L || newQuantity > Long.MAX_VALUE) {
                        throw new IllegalArgumentException("Quantities must be nonnegative and below Long.MAX_VALUE.");
                    }
                    committedQuantities[i] = newQuantity;
                }

                currentOwnerData = null;
                return true;
            }
        }

        public boolean discard(String ownerId, long generation) {
            synchronized (lock) {
                if (currentOwnerData == null || !ownerId.equals(currentOwnerData.ownerId)) {
                    return false;
                }

                if (generation != currentOwnerData.generation) {
                    return false;
                }

                currentOwnerData = null;
                return true;
            }
        }

        private static class OwnerData {
            public final String ownerId;
            public final long[] delta;
            public final Set<String> tagsSet;
            public final long generation;

            public OwnerData(String ownerId, long[] delta, String[] tags) {
                this.ownerId = ownerId;
                this.delta = delta;
                this.tagsSet = new HashSet<>(Arrays.asList(tags));
                generation = currentOwnerData != null ? currentOwnerData.generation + 1 : 1L;
            }
        }
    }
}
