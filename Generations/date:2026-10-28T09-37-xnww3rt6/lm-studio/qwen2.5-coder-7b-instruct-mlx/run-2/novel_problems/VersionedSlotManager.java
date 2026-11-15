@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private long revision;
    private Reservation[] slots;

    public VersionedSlotManager() {
        this.revision = 0;
        this.slots = new Reservation[128];
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Reservation[] snapshot = new Reservation[slots.length];
            System.arraycopy(slots, 0, snapshot, 0, slots.length);
            return new Snapshot(snapshot, revision);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        synchronized (lock) {
            checkParameters(start, end, expectedRevision);
            if (expectedRevision != revision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, -1);
            }
            for (Reservation slot : slots) {
                if (slot != null && slot.end > start && slot.start < end) {
                    return new MutationResult(MutationStatus.CONFLICT, revision, -1);
                }
            }
            Reservation newSlot = new Reservation(start, end, revision + 1, slots.length);
            int index = findIndex(newSlot.start, slots);
            if (index == -1) {
                throw new IllegalStateException("Failed to allocate slot");
            }
            slots[index] = newSlot;
            revision++;
            return new MutationResult(MutationStatus.SUCCESS, revision, newSlot.id);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        synchronized (lock) {
            checkParameters(reservationId, expectedRevision);
            if (expectedRevision != revision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, -1);
            }
            int index = findIndexById(reservationId, slots);
            if (index == -1) {
                return new MutationResult(MutationStatus.NOT_FOUND, revision, -1);
            }
            slots[index] = null;
            revision++;
            return new MutationResult(MutationStatus.SUCCESS, revision, -1);
        }
    }

    private void checkParameters(long start, long end, long expectedRevision) {
        if (start >= end || start < 0 || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid parameters");
        }
    }

    private void checkParameters(long id, long expectedRevision) {
        if (id < 1 || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid parameters");
        }
    }

    private int findIndex(long start, Reservation[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private int findIndexById(long id, Reservation[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].id == id) {
                return i;
            }
        }
        return -1;
    }

    public static class Snapshot {
        private final Reservation[] slots;
        private final long revision;

        public Snapshot(Reservation[] slots, long revision) {
            this.slots = slots;
            this.revision = revision;
        }

        public Reservation[] getSlots() {
            return slots.clone();
        }
    }

    public static class Reservation {
        private final long start;
        private final long end;
        private final long id;
        private final int index;

        public Reservation(long start, long end, long id, int index) {
            this.start = start;
            this.end = end;
            this.id = id;
            this.index = index;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }

        public long getId() {
            return id;
        }
    }

    public static class MutationResult {
        private final MutationStatus status;
        private final long revision;
        private final long id;

        public MutationResult(MutationStatus status, long revision, long id) {
            this.status = status;
            this.revision = revision;
            this.id = id;
        }

        public MutationStatus getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }

        public long getId() {
            return id;
        }
    }

    public enum MutationStatus {
        SUCCESS, STALE_REVISION, CONFLICT, NOT_FOUND
    }
}
