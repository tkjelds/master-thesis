@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}


    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

    /** Immutable detached copy of a reserved slot. */
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

        @Override
        public String toString() {
            return "Slot[id=" + id + ", start=" + start + ", end=" + end + "]";
        }
    }

    /** Immutable snapshot of all slots together with the revision captured with them. */
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

        /** Returns a fresh copy of the slot array; slots themselves are immutable. */
        public Slot[] getSlots() {
            return slots.clone();
        }

        public int size() {
            return slots.length;
        }
    }

    /** Result of a reserve call. reservationId is positive only when status is SUCCESS. */
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

        /** Resulting revision on SUCCESS; observed current revision otherwise. */
        public long getRevision() {
            return revision;
        }

        public long getReservationId() {
            return reservationId;
        }
    }

    /** Result of a cancel call. */
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

        /** Resulting revision on SUCCESS; observed current revision otherwise. */
        public long getRevision() {
            return revision;
        }
    }

    // Blocking policy: no operation blocks or waits. Every call either completes
    // immediately with an explicit status or throws. The monitor is held only for
    // short, bounded critical sections, so no waiting is ever required.
    private final Object lock = new Object();

    // All fields below are guarded by lock.
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
            throw new IllegalArgumentException("start must be less than end: [" + start + ", " + end + "]");
        }
        if (expectedRevision < 0) {
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
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID space exhausted");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            ensureCapacity();
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
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new CancelResult(Status.STALE_REVISION, revision);
            }
            int index = -1;
            for (int i = 0; i < count; i++) {
                if (ids[i] == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return new CancelResult(Status.NOT_FOUND, revision);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            int last = count - 1;
            if (index != last) {
                ids[index] = ids[last];
                starts[index] = starts[last];
                ends[index] = ends[last];
            }
            ids[last] = 0L;
            starts[last] = 0L;
            ends[last] = 0L;
            count--;
            revision++;
            return new CancelResult(Status.SUCCESS, revision);
        }
    }

    // Must be called while holding lock.
    private void ensureCapacity() {
        if (count < ids.length) {
            return;
        }
        int newCapacity = ids.length * 2;
        long[] newIds = new long[newCapacity];
        long[] newStarts = new long[newCapacity];
        long[] newEnds = new long[newCapacity];
        System.arraycopy(ids, 0, newIds, 0, count);
        System.arraycopy(starts, 0, newStarts, 0, count);
        System.arraycopy(ends, 0, newEnds, 0, count);
        ids = newIds;
        starts = newStarts;
        ends = newEnds;
    }
}
