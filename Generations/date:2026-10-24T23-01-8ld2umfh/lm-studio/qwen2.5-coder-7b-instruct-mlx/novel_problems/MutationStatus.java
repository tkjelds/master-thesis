@VersionedSlotManager.ThreadSafe
class VersionedSlotManager {
    @interface ThreadSafe {}

    private Slot[] slots;
    private int revision;
    private long nextId;

    public VersionedSlotManager(int capacity) {
        slots = new Slot[capacity];
        revision = 0;
        nextId = 1;
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] copy = new Slot[slots.length];
            System.arraycopy(slots, 0, copy, 0, slots.length);
            return new Snapshot(copy, revision);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            if (revision != expectedRevision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, -1);
            }
            if (isOverlapping(start, end)) {
                return new MutationResult(MutationStatus.CONFLICT, revision, -1);
            }
            int id = nextId++;
            Slot newSlot = new Slot(start, end, id);
            slots[newSlot.index] = newSlot;
            revision++;
            return new MutationResult(MutationStatus.SUCCESS, revision, id);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (this) {
            if (revision != expectedRevision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, -1);
            }
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null && slots[i].id == reservationId) {
                    slots[i] = null;
                    revision++;
                    return new MutationResult(MutationStatus.SUCCESS, revision, -1);
                }
            }
            return new MutationResult(MutationStatus.NOT_FOUND, revision, -1);
        }
    }

    private boolean isOverlapping(long start, long end) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].start < end && slots[i].end > start) {
                return true;
            }
        }
        return false;
    }

    private static class Slot {
        long start;
        long end;
        int id;
        int index;

        Slot(long start, long end, int id) {
            this.start = start;
            this.end = end;
            this.id = id;
        }
    }

    public static class Snapshot {
        Slot[] slots;
        int revision;

        Snapshot(Slot[] slots, int revision) {
            this.slots = slots;
            this.revision = revision;
        }
    }

    public static class MutationResult {
        MutationStatus status;
        int observedRevision;
        long reservationId;

        MutationResult(MutationStatus status, int observedRevision, long reservationId) {
            this.status = status;
            this.observedRevision = observedRevision;
            this.reservationId = reservationId;
        }
    }

    public enum MutationStatus {
        SUCCESS, STALE_REVISION, CONFLICT, NOT_FOUND
    }
}
