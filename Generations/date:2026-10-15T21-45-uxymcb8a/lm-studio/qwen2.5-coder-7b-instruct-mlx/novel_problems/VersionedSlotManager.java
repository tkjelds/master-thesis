@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final int MAX_REVISION = Integer.MAX_VALUE;
    private static final int SUCCESS = 0;
    private static final int STALE_REVISION = 1;
    private static final int CONFLICT = 2;
    private static final int NOT_FOUND = 3;

    private class Slot {
        long start;
        long end;
        int id;

        public Slot(long start, long end) {
            if (start >= end || start < 0 || end > MAX_REVISION) {
                throw new IllegalArgumentException("Invalid slot interval");
            }
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Slot)) return false;
            Slot slot = (Slot) o;
            return start == slot.start && end == slot.end;
        }
    }

    private class Snapshot {
        int revision;
        Slot[] slots;

        public Snapshot(int revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots.clone();
        }
    }

    private int currentRevision;
    private Slot[] slots;

    public VersionedSlotManager() {
        this.currentRevision = 0;
        this.slots = new Slot[0];
    }

    public synchronized Snapshot getSnapshot() {
        return new Snapshot(currentRevision, slots);
    }

    public synchronized int reserve(long start, long end, long expectedRevision) {
        if (start >= end || start < 0 || end > MAX_REVISION) {
            throw new IllegalArgumentException("Invalid slot interval");
        }
        if (expectedRevision < 0 || expectedRevision > currentRevision) {
            throw new IllegalStateException("Invalid revision");
        }

        for (Slot slot : slots) {
            if (!(slot.end <= start || end <= slot.start)) {
                return CONFLICT;
            }
        }

        int id = currentRevision + slots.length + 1;
        Slot newSlot = new Slot(start, end);
        Slot[] newSlots = new Slot[slots.length + 1];
        System.arraycopy(slots, 0, newSlots, 0, slots.length);
        newSlots[slots.length] = newSlot;

        currentRevision++;
        slots = newSlots;
        return SUCCESS;
    }

    public synchronized int cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0 || expectedRevision > currentRevision) {
            throw new IllegalStateException("Invalid revision");
        }

        for (int i = 0; i < slots.length; i++) {
            if (slots[i].id == reservationId) {
                Slot[] newSlots = new Slot[slots.length - 1];
                System.arraycopy(slots, 0, newSlots, 0, i);
                System.arraycopy(slots, i + 1, newSlots, i, slots.length - i - 1);
                slots = newSlots;
                currentRevision++;
                return SUCCESS;
            }
        }

        return NOT_FOUND;
    }
}
