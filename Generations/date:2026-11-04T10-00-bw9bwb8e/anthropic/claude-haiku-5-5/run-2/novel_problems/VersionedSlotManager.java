@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}


    public static final class Slot {
        private final long id;
        private final long start;
        private final long end;

        private Slot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long getId() {
            return id;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    public static final class Snapshot {
        private final long revision;
        private final Slot[] slots;

        private Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }

        public long getRevision() {
            return revision;
        }

        public int size() {
            return slots.length;
        }

        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                copy[i] = slots[i];
            }
            return copy;
        }
    }

    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

    public static final class ReserveResult {
        private final Status status;
        private final long revision;
        private final long reservationId;

        private ReserveResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public Status getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }

        public long getReservationId() {
            return reservationId;
        }
    }

    public static final class CancelResult {
        private final Status status;
        private final long revision;

        private CancelResult(Status status, long revision) {
            this.status = status;
            this.revision = revision;
        }

        public Status getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }
    }

    // All mutable state is guarded by 'lock'. The operations never block
    // beyond acquiring the monitor: conflicts and stale revisions are
    // reported through status results rather than waiting.
    private final Object lock = new Object();
    private long revision = 0L;
    private long nextId = 1L;
    private long[] ids = new long[8];
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private int count = 0;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] copy = new Slot[count];
            for (int i = 0; i < count; i++) {
                copy[i] = new Slot(ids[i], starts[i], ends[i]);
            }
            return new Snapshot(revision, copy);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("invalid interval: start must be < end");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new ReserveResult(Status.STALE_REVISION, revision, 0L);
            }
            for (int i = 0; i < count; i++) {
                // Closed intervals: touching endpoints conflict.
                if (start <= ends[i] && starts[i] <= end) {
                    return new ReserveResult(Status.CONFLICT, revision, 0L);
                }
            }
            if (revision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("revision or id space exhausted");
            }
            ensureCapacity(count + 1);
            long id = nextId;
            nextId++;
            ids[count] = id;
            starts[count] = start;
            ends[count] = end;
            count++;
            revision++;
            return new ReserveResult(Status.SUCCESS, revision, id);
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new CancelResult(Status.STALE_REVISION, revision);
            }
            int idx = -1;
            for (int i = 0; i < count; i++) {
                if (ids[i] == reservationId) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return new CancelResult(Status.NOT_FOUND, revision);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            for (int i = idx; i < count - 1; i++) {
                ids[i] = ids[i + 1];
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
            }
            count--;
            ids[count] = 0L;
            starts[count] = 0L;
            ends[count] = 0L;
            revision++;
            return new CancelResult(Status.SUCCESS, revision);
        }
    }

    // Must be called while holding 'lock'.
    private void ensureCapacity(int needed) {
        if (needed <= ids.length) {
            return;
        }
        int newCap = ids.length;
        while (newCap < needed) {
            newCap = newCap * 2;
        }
        long[] newIds = new long[newCap];
        long[] newStarts = new long[newCap];
        long[] newEnds = new long[newCap];
        for (int i = 0; i < count; i++) {
            newIds[i] = ids[i];
            newStarts[i] = starts[i];
            newEnds[i] = ends[i];
        }
        ids = newIds;
        starts = newStarts;
        ends = newEnds;
    }
}
