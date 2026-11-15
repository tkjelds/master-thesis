@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final int SUCCESS = 0;
    private static final int STALE_REVISION = 1;
    private static final int CONFLICT = 2;
    private static final int NOT_FOUND = 3;

    private class Slot {
        long start;
        long end;
        int id;

        Slot(long start, long end, int id) {
            this.start = start;
            this.end = end;
            this.id = id;
        }
    }

    private class Snapshot {
        int revision;
        Slot[] slots;

        Snapshot(int revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots.clone();
        }
    }

    private int nextId;
    private int currentRevision;
    private Slot[] slots;

    public VersionedSlotManager() {
        this.nextId = 1;
        this.currentRevision = 0;
        this.slots = new Slot[0];
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            return new Snapshot(currentRevision, slots);
        }
    }

    public int[] reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) throw new IllegalArgumentException();
        int id = nextId++;
        synchronized (this) {
            if (expectedRevision != currentRevision) return new int[]{STALE_REVISION, -1, -1};
            Slot[] newSlots = Arrays.copyOf(slots, slots.length + 1);
            newSlots[newSlots.length - 1] = new Slot(start, end, id);
            Arrays.sort(newSlots, (s1, s2) -> Long.compare(s1.start, s2.start));
            boolean conflict = false;
            for (int i = 0; i < newSlots.length - 1; i++) {
                if (newSlots[i].end > start && newSlots[i + 1].start < end) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) return new int[]{CONFLICT, currentRevision, -1};
            slots = newSlots;
            currentRevision++;
            return new int[]{SUCCESS, currentRevision, id};
        }
    }

    public int[] cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException();
        synchronized (this) {
            if (expectedRevision != currentRevision) return new int[]{STALE_REVISION, -1};
            Slot[] newSlots = Arrays.stream(slots).filter(s -> s.id != reservationId).toArray(Slot[]::new);
            slots = newSlots;
            currentRevision++;
            return new int[]{SUCCESS, currentRevision, -1};
        }
    }

    private boolean withinBounds() {
        return nextId <= Integer.MAX_VALUE - 1 && currentRevision < Integer.MAX_VALUE;
    }
}
