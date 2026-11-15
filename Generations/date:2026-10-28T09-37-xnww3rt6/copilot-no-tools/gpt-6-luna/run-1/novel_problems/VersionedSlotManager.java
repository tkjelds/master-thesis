@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    public enum ReserveStatus {
        SUCCESS,
        STALE_REVISION,
        CONFLICT
    }

    public enum CancelStatus {
        SUCCESS,
        STALE_REVISION,
        NOT_FOUND
    }

    public static final class Slot {
        public final long id;
        public final long start;
        public final long end;

        private Slot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Snapshot {
        public final long revision;
        private final Slot[] slots;

        private Snapshot(long revision, Slot[] source, int size) {
            this.revision = revision;
            this.slots = new Slot[size];
            for (int i = 0; i < size; i++) {
                Slot slot = source[i];
                this.slots[i] = new Slot(slot.id, slot.start, slot.end);
            }
        }

        public int size() {
            return slots.length;
        }

        public Slot getSlot(int index) {
            if (index < 0 || index >= slots.length) {
                throw new IndexOutOfBoundsException();
            }
            Slot slot = slots[index];
            return new Slot(slot.id, slot.start, slot.end);
        }

        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(slot.id, slot.start, slot.end);
            }
            return copy;
        }
    }

    public static final class ReserveResult {
        public final ReserveStatus status;
        public final long revision;
        public final long reservationId;

        private ReserveResult(ReserveStatus status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }
    }

    public static final class CancelResult {
        public final CancelStatus status;
        public final long revision;

        private CancelResult(CancelStatus status, long revision) {
            this.status = status;
            this.revision = revision;
        }
    }

    private final Object lock = new Object();
    private Slot[] slots = new Slot[0];
    private int size;
    private long revision;
    private long lastAssignedId;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            return new Snapshot(revision, slots, size);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < size; i++) {
                Slot existing = slots[i];
                if (start <= existing.end && existing.start <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }
            if (lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation IDs exhausted");
            }

            Slot[] destination = slots;
            if (size == slots.length) {
                if (slots.length == Integer.MAX_VALUE) {
                    throw new IllegalStateException("too many reservations");
                }
                int newCapacity = slots.length == 0 ? 1
                        : (slots.length > Integer.MAX_VALUE / 2
                        ? Integer.MAX_VALUE : slots.length * 2);
                destination = new Slot[newCapacity];
                for (int i = 0; i < size; i++) {
                    destination[i] = slots[i];
                }
            }

            long id = lastAssignedId + 1;
            destination[size] = new Slot(id, start, end);
            slots = destination;
            size++;
            lastAssignedId = id;
            revision++;
            return new ReserveResult(ReserveStatus.SUCCESS, revision, id);
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new CancelResult(CancelStatus.STALE_REVISION, revision);
            }

            int index = -1;
            for (int i = 0; i < size; i++) {
                if (slots[i].id == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return new CancelResult(CancelStatus.NOT_FOUND, revision);
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }

            for (int i = index; i < size - 1; i++) {
                slots[i] = slots[i + 1];
            }
            slots[size - 1] = null;
            size--;
            revision++;
            return new CancelResult(CancelStatus.SUCCESS, revision);
        }
    }
}
