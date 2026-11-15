@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final long INVALID_REVISION = -1;
    private static final int SUCCESS = 0;
    private static final int STALE_REVISION = 1;
    private static final int CONFLICT = 2;
    private static final int NOT_FOUND = 3;

    private class Slot {
        long start;
        long end;
        int id;
        boolean isReserved;

        Slot(long start, long end) {
            this.start = start;
            this.end = end;
            this.isReserved = true;
        }
    }

    private class Snapshot {
        Slot[] slots;
        long revision;

        Snapshot(Slot[] slots, long revision) {
            this.slots = slots;
            this.revision = revision;
        }
    }

    private class MutationResult {
        int status;
        long observedRevision;
        int id;

        MutationResult(int status, long observedRevision, int id) {
            this.status = status;
            this.observedRevision = observedRevision;
            this.id = id;
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
            return new Snapshot(slots.clone(), revision);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException();
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(STALE_REVISION, revision, INVALID_REVISION);
            }

            if (isConflicting(start, end)) {
                return new MutationResult(CONFLICT, revision, INVALID_REVISION);
            }

            int id = (int) revision;
            slots = Arrays.copyOf(slots, slots.length + 1);
            slots[slots.length - 1] = new Slot(start, end);
            revision++;
            return new MutationResult(SUCCESS, revision, id);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(STALE_REVISION, revision, INVALID_REVISION);
            }

            int index = findSlotByReservationId(reservationId);
            if (index == -1) {
                return new MutationResult(NOT_FOUND, revision, INVALID_REVISION);
            }

            slots[index].isReserved = false;
            revision++;
            return new MutationResult(SUCCESS, revision, INVALID_REVISION);
        }
    }

    private boolean isConflicting(long start, long end) {
        for (Slot slot : slots) {
            if (slot.start <= start && end <= slot.end || slot.start >= start && start <= slot.end) {
                return true;
            }
        }
        return false;
    }

    private int findSlotByReservationId(int reservationId) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isReserved && slots[i].id == reservationId) {
                return i;
            }
        }
        return -1;
    }
}
