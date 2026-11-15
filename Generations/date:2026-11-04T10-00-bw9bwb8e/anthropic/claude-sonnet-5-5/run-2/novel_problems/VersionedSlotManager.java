@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}


    public enum Status {
        SUCCESS, STALE_REVISION, CONFLICT, NOT_FOUND
    }

    /** Immutable slot value. */
    public static final class Slot {
        private final long id;
        private final long start;
        private final long end;

        Slot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long getId() { return id; }
        public long getStart() { return start; }
        public long getEnd() { return end; }

        @Override
        public String toString() {
            return "Slot{id=" + id + ", [" + start + "," + end + "]}";
        }
    }

    /** Immutable, detached snapshot. */
    public static final class Snapshot {
        private final Slot[] slots;
        private final long revision;

        Snapshot(Slot[] slots, long revision) {
            this.slots = slots;
            this.revision = revision;
        }

        public long getRevision() { return revision; }

        public int size() { return slots.length; }

        public Slot getSlot(int index) { return slots[index]; }

        /** Returns a fresh copy of the slots array, ordered by start. */
        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                copy[i] = slots[i];
            }
            return copy;
        }
    }

    /** Immutable result of a mutation attempt. */
    public static final class MutationResult {
        private final Status status;
        private final long revision;
        private final long reservationId;

        MutationResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public Status getStatus() { return status; }

        /** Observed revision on failure; resulting revision on success. */
        public long getRevision() { return revision; }

        /** ID assigned (reserve) or removed (cancel) on success; 0 otherwise. */
        public long getReservationId() { return reservationId; }

        @Override
        public String toString() {
            return "MutationResult{" + status + ", revision=" + revision
                    + ", id=" + reservationId + "}";
        }
    }

    private final Object lock = new Object();
    // Guarded by lock; kept sorted by start.
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private long[] ids = new long[8];
    private int count = 0;
    private long revision = 0;
    private long nextId = 1;
    private boolean idsExhausted = false;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] copy = new Slot[count];
            for (int i = 0; i < count; i++) {
                copy[i] = new Slot(ids[i], starts[i], ends[i]);
            }
            return new Snapshot(copy, revision);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }
            for (int i = 0; i < count; i++) {
                if (start <= ends[i] && starts[i] <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }
            if (revision == Long.MAX_VALUE || idsExhausted) {
                throw new IllegalStateException("revision or id space exhausted");
            }
            if (count == starts.length) {
                int newCap = count * 2;
                long[] ns = new long[newCap];
                long[] ne = new long[newCap];
                long[] ni = new long[newCap];
                for (int i = 0; i < count; i++) {
                    ns[i] = starts[i];
                    ne[i] = ends[i];
                    ni[i] = ids[i];
                }
                starts = ns;
                ends = ne;
                ids = ni;
            }
            int pos = count;
            while (pos > 0 && starts[pos - 1] > start) {
                starts[pos] = starts[pos - 1];
                ends[pos] = ends[pos - 1];
                ids[pos] = ids[pos - 1];
                pos--;
            }
            long id = nextId;
            starts[pos] = start;
            ends[pos] = end;
            ids[pos] = id;
            count++;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            revision++;
            return new MutationResult(Status.SUCCESS, revision, id);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }
            int idx = -1;
            for (int i = 0; i < count; i++) {
                if (ids[i] == reservationId) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            for (int i = idx; i < count - 1; i++) {
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
                ids[i] = ids[i + 1];
            }
            count--;
            starts[count] = 0;
            ends[count] = 0;
            ids[count] = 0;
            revision++;
            return new MutationResult(Status.SUCCESS, revision, reservationId);
        }
    }
}
