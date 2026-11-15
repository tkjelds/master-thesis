import java.util.Arrays;

@InventoryTransactionBuffer.ThreadSafe
public class InventoryTransactionBuffer {
    @interface ThreadSafe {}

    private final long[] committed;
    private StagingSlot stageSlot = new StagingSlot();
    private long nextGeneration = 1;

    public InventoryTransactionBuffer(long[] committed) {
        if (committed == null || committed.length == 0) {
            throw new IllegalArgumentException("Initial committed quantities must be nonempty");
        }
        this.committed = Arrays.copyOf(committed, committed.length);
    }

    public long stage(String ownerId, long[] delta, String[] tags) {
        if (ownerId == null || ownerId.isEmpty() || delta == null || tags == null) {
            throw new IllegalArgumentException("Invalid inputs");
        }
        synchronized (stageSlot) {
            // Check if the delta array length matches the committed quantities length
            if (delta.length != committed.length) {
                throw new IllegalArgumentException("Delta array must match the committed quantities length");
            }
            // Check if projected quantities are within valid range
            for (int i = 0; i < delta.length; i++) {
                if (committed[i] + delta[i] > Long.MAX_VALUE) {
                    throw new IllegalArgumentException("Projected quantity exceeds Long.MAX_VALUE");
                }
            }
            stageSlot.ownerId = ownerId;
            stageSlot.delta = Arrays.copyOf(delta, delta.length);
            stageSlot.tags = Arrays.copyOf(tags, tags.length);
            return nextGeneration++;
        }
    }

    public boolean commit(String ownerId, long generation, String[] required, String[] forbidden) {
        if (ownerId == null || ownerId.isEmpty() || required == null || forbidden == null) {
            throw new IllegalArgumentException("Invalid inputs");
        }
        synchronized (stageSlot) {
            if (generation != nextGeneration - 1 || !ownerId.equals(stageSlot.ownerId)) {
                return false;
            }
            // Check if all required tags are present
            for (String tag : required) {
                boolean found = false;
                for (String slotTag : stageSlot.tags) {
                    if (slotTag.equals(tag)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            // Check if any forbidden tags are present
            for (String tag : forbidden) {
                for (String slotTag : stageSlot.tags) {
                    if (slotTag.equals(tag)) {
                        return false;
                    }
                }
            }
            // Apply the delta to committed quantities
            for (int i = 0; i < stageSlot.delta.length; i++) {
                committed[i] += stageSlot.delta[i];
            }
            // Clear the staging slot
            stageSlot.ownerId = null;
            stageSlot.delta = null;
            stageSlot.tags = null;
            return true;
        }
    }

    public boolean discard(String ownerId, long generation) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("Invalid inputs");
        }
        synchronized (stageSlot) {
            if (generation != nextGeneration - 1 || !ownerId.equals(stageSlot.ownerId)) {
                return false;
            }
            // Clear the staging slot
            stageSlot.ownerId = null;
            stageSlot.delta = null;
            stageSlot.tags = null;
            return true;
        }
    }

    public long[] snapshot() {
        // Return a copy of the committed quantities
        return Arrays.copyOf(committed, committed.length);
    }

    private static class StagingSlot {
        String ownerId;
        long[] delta;
        String[] tags;
    }
}
