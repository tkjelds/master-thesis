import java.lang.*;

@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}


    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

    /** Immutable detached slot. */
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
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Slot)) return false;
            Slot s = (Slot) o;
            return id == s.id && start == s.start && end == s.end;
        }

        @Override
        public int hashCode() {
            int h = (int) (id ^ (id >>> 32));
            h = 31 * h + (int) (start ^ (start >>> 32));
            h = 31 * h + (int) (end ^ (end >>> 32));
            return h;
        }

        @Override
        public String toString() {
            return "Slot{id=" + id + ", [" + start + ", " + end + "]}";
        }
    }

    /** Immutable snapshot: revision and a private copy of slots. */
    public static final class Snapshot {
        private final long revision;
        private final Slot[] slots;

        private Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }

        public long getRevision() { return revision; }

        public int size() { return slots.length; }

        public Slot getSlot(int index) { return slots[index]; }

        /** Returns a fresh copy each call. */
        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            System.arraycopy(slots, 0, copy, 0, slots.length);
            return copy;
        }
    }

    /** Result of a mutation. reservationId is the assigned (reserve) or removed (cancel) ID on success, else 0. */
    public static final class MutationResult {
        private final Status status;
        private final long revision;
        private final long reservationId;

        private MutationResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public Status getStatus() { return status; }
        public long getRevision() { return revision; }
        public long getReservationId() { return reservationId; }

        @Override
        public String toString() {
            return "MutationResult{" + status + ", revision=" + revision + ", id=" + reservationId + "}";
        }
    }

    private final Object lock = new Object();
    // Guarded by lock; kept sorted by start.
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private long[] ids = new long[8];
    private int count = 0;
    private long revision = 0;
    private long lastId = 0;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] copy = new Slot[count];
            for (int i = 0; i < count; i++) {
                copy[i] = new Slot(ids[i], starts[i], ends[i]);
            }
            return new Snapshot(revision, copy);
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
                if (starts[i] <= end && start <= ends[i]) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }
            if (revision == Long.MAX_VALUE || lastId == Long.MAX_VALUE) {
                throw new IllegalStateException("revision or id space exhausted");
            }
            if (count == starts.length) {
                int n = count * 2;
                long[] ns = new long[n];
                long[] ne = new long[n];
                long[] ni = new long[n];
                System.arraycopy(starts, 0, ns, 0, count);
                System.arraycopy(ends, 0, ne, 0, count);
                System.arraycopy(ids, 0, ni, 0, count);
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
            long id = ++lastId;
            starts[pos] = start;
            ends[pos] = end;
            ids[pos] = id;
            count++;
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
            if (reservationId > 0) {
                for (int i = 0; i < count; i++) {
                    if (ids[i] == reservationId) {
                        idx = i;
                        break;
                    }
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
