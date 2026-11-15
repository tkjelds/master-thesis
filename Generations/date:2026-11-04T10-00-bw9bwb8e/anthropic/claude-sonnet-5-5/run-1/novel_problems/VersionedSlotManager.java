@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}


    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

    public static final class Slot {
        private final long id;
        private final long start;
        private final long end;

        Slot(long id, long start, long end) {
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
            return "Slot{id=" + id + ", [" + start + ", " + end + "]}";
        }
    }

    public static final class Snapshot {
        private final long revision;
        private final Slot[] slots;

        Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }

        public long getRevision() {
            return revision;
        }

        public int size() {
            return slots.length;
        }

        public Slot get(int index) {
            return slots[index];
        }

        public Slot[] getSlots() {
            return slots.clone();
        }
    }

    public static final class MutationResult {
        private final Status status;
        private final long revision;
        private final long id;

        MutationResult(Status status, long revision, long id) {
            this.status = status;
            this.revision = revision;
            this.id = id;
        }

        public Status getStatus() {
            return status;
        }

        /** Observed revision on failure, resulting revision on success. */
        public long getRevision() {
            return revision;
        }

        /** Assigned ID on successful reserve, the cancelled ID on successful cancel, otherwise 0. */
        public long getId() {
            return id;
        }

        @Override
        public String toString() {
            return "MutationResult{" + status + ", revision=" + revision + ", id=" + id + "}";
        }
    }

    private final Object lock = new Object();

    // Guarded by lock
    private long[] ids = new long[8];
    private long[] starts = new long[8];
    private long[] ends = new long[8];
    private int count = 0;
    private long revision = 0;
    private long nextId = 1;

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
            throw new IllegalArgumentException("start must be < end");
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
            if (revision == Long.MAX_VALUE || nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("revision or id space exhausted");
            }
            if (count == ids.length) {
                int newCap = ids.length * 2;
                long[] ni = new long[newCap];
                long[] ns = new long[newCap];
                long[] ne = new long[newCap];
                for (int i = 0; i < count; i++) {
                    ni[i] = ids[i];
                    ns[i] = starts[i];
                    ne[i] = ends[i];
                }
                ids = ni;
                starts = ns;
                ends = ne;
            }
            long id = nextId++;
            ids[count] = id;
            starts[count] = start;
            ends[count] = end;
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
                ids[i] = ids[i + 1];
                starts[i] = starts[i + 1];
                ends[i] = ends[i + 1];
            }
            count--;
            ids[count] = 0;
            starts[count] = 0;
            ends[count] = 0;
            revision++;
            return new MutationResult(Status.SUCCESS, revision, reservationId);
        }
    }
}
