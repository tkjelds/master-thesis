@VersionedSlotManager.ThreadSafe
class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final long INVALID_REVISION = -1;
    private static final int SUCCESS = 0;
    private static final int STALE_REVISION = 1;
    private static final int CONFLICT = 2;
    private static final int NOT_FOUND = 3;

    private static class Slot {
        long start, end, id;
        Slot(long start, long end) {
            this.start = start;
            this.end = end;
            this.id = INVALID_REVISION;
        }
    }

    private static class Snapshot {
        long revision;
        Slot[] slots;

        Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }
    }

    private Slot[] slots;
    private long revision;

    public VersionedSlotManager() {
        this.slots = new Slot[0];
        this.revision = 0;
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            return new Snapshot(revision, slots.clone());
        }
    }

    public int reserve(long start, long end, long expectedRevision, long[] result) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid interval or negative expected revision");
        }

        synchronized (this) {
            if (revision != expectedRevision) {
                result[0] = STALE_REVISION;
                return result[1] = revision;
            }

            for (Slot slot : slots) {
                if (!(start > slot.end || end < slot.start)) {
                    result[0] = CONFLICT;
                    return result[1] = revision;
                }
            }

            Slot newSlot = new Slot(start, end);
            long id = revision + 1;
            if (id < 0) {
                throw new IllegalStateException("Repetition number overflow");
            }
            newSlot.id = id;
            slots = Arrays.copyOf(slots, slots.length + 1);
            slots[slots.length - 1] = newSlot;
            revision++;
            result[0] = SUCCESS;
            return result[1] = id;
        }
    }

    public int cancel(long reservationId, long expectedRevision, long[] result) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Negative expected revision");
        }

        synchronized (this) {
            if (revision != expectedRevision) {
                result[0] = STALE_REVISION;
                return result[1] = revision;
            }

            Slot[] newSlots = Arrays.stream(slots)
                    .filter(slot -> slot.id != reservationId)
                    .toArray(Slot[]::new);

            if (slots.length == newSlots.length) {
                result[0] = NOT_FOUND;
                return result[1] = revision;
            }

            slots = newSlots;
            revision++;
            result[0] = SUCCESS;
            return result[1] = revision;
        }
    }
}
