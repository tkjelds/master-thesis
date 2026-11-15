@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}


    public enum ReserveStatus { SUCCESS, STALE_REVISION, CONFLICT }

    public enum CancelStatus { SUCCESS, STALE_REVISION, NOT_FOUND }

    /** Immutable, detached copy of a reserved slot. */
    public static final class Slot {
        private final long id;
        private final long start;
        private final long end;

        public Slot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long getId() { return id; }
        public long getStart() { return start; }
        public long getEnd() { return end; }

        @Override
        public String toString() {
            return "Slot[id=" + id + ", start=" + start + ", end=" + end + "]";
        }
    }

    /** Immutable snapshot of the manager state, captured atomically with its revision. */
    public static final class Snapshot {
        private final long revision;
        private final Slot[] slots;

        Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }

        public long getRevision() { return revision; }

        /** Returns a fresh copy; callers cannot alter retained state. */
        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            System.arraycopy(slots, 0, copy, 0, slots.length);
            return copy;
        }

        public int size() { return slots.length; }
    }

    /** Result of a reserve operation. reservationId is 0 unless status is SUCCESS. */
    public static final class ReserveResult {
        private final ReserveStatus status;
        private final long revision;
        private final long reservationId;

        ReserveResult(ReserveStatus status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public ReserveStatus getStatus() { return status; }
        public long getRevision() { return revision; }
        public long getReservationId() { return reservationId; }
    }

    /** Result of a cancel operation. */
    public static final class CancelResult {
        private final CancelStatus status;
        private final long revision;

        CancelResult(CancelStatus status, long revision) {
            this.status = status;
            this.revision = revision;
        }

        public CancelStatus getStatus() { return status; }
        public long getRevision() { return revision; }
    }

    // ---- Shared state: all accesses guarded by 'lock' ----
    // Policy: operations never block on conditions; they complete immediately
    // with an explicit status. The only waiting is monitor acquisition.
    private final Object lock = new Object();
    private long revision = 0L;
    private long nextId = 1L;
    private long[] ids = new long[16];
    private long[] starts = new long[16];
    private long[] ends = new long[16];
    private int count = 0;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] slots = new Slot[count];
            for (int i = 0; i < count; i++) {
                slots[i] = new Slot(ids[i], starts[i], ends[i]);
            }
            return new Snapshot(revision, slots);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0L);
            }
            for (int i = 0; i < count; i++) {
                if (start <= ends[i] && starts[i] <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0L);
                }
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID overflow");
            }
            long id = nextId;
            if (count == ids.length) {
                int newCap = ids.length * 2;
                long[] nIds = new long[newCap];
                long[] nStarts = new long[newCap];
                long[] nEnds = new long[newCap];
                System.arraycopy(ids, 0, nIds, 0, count);
                System.arraycopy(starts, 0, nStarts, 0, count);
                System.arraycopy(ends, 0, nEnds, 0, count);
                ids = nIds;
                starts = nStarts;
                ends = nEnds;
            }
            ids[count] = id;
            starts[count] = start;
            ends[count] = end;
            count++;
            nextId = id + 1;
            revision = revision + 1;
            return new ReserveResult(ReserveStatus.SUCCESS, revision, id);
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new CancelResult(CancelStatus.STALE_REVISION, revision);
            }
            int index = -1;
            for (int i = 0; i < count; i++) {
                if (ids[i] == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return new CancelResult(CancelStatus.NOT_FOUND, revision);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision overflow");
            }
            int tail = count - index - 1;
            if (tail > 0) {
                System.arraycopy(ids, index + 1, ids, index, tail);
                System.arraycopy(starts, index + 1, starts, index, tail);
                System.arraycopy(ends, index + 1, ends, index, tail);
            }
            count--;
            ids[count] = 0L;
            starts[count] = 0L;
            ends[count] = 0L;
            revision = revision + 1;
            return new CancelResult(CancelStatus.SUCCESS, revision);
        }
    }
}
