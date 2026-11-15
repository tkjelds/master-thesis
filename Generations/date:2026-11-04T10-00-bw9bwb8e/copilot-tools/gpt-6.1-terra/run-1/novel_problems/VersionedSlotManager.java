@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

    public static final class Slot {
        private final long reservationId;
        private final long start;
        private final long end;

        private Slot(long reservationId, long start, long end) {
            this.reservationId = reservationId;
            this.start = start;
            this.end = end;
        }

        public long getReservationId() {
            return reservationId;
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

        public Slot[] getSlots() {
            return slots.clone();
        }
    }

    public static final class MutationResult {
        private final Status status;
        private final long revision;
        private final long reservationId;

        private MutationResult(Status status, long revision, long reservationId) {
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

        // Zero indicates that the operation did not succeed.
        public long getReservationId() {
            return reservationId;
        }
    }

    private final Object lock = new Object();
    private Slot[] slots = new Slot[8];
    private int size;
    private long revision;
    private long lastIssuedId;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] copy = new Slot[size];
            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copy);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expected revision must be nonnegative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE || lastIssuedId == Long.MAX_VALUE) {
                throw new IllegalStateException("revision or reservation ID exhausted");
            }

            long newId = lastIssuedId + 1;
            long newRevision = revision + 1;
            Slot newSlot = new Slot(newId, start, end);
            MutationResult result = new MutationResult(Status.SUCCESS, newRevision, newId);

            // Storage grows as needed; operations never wait for capacity.
            if (size == slots.length) {
                if (size == Integer.MAX_VALUE) {
                    throw new IllegalStateException("slot capacity exhausted");
                }
                long proposed = (long) slots.length * 2;
                int newCapacity = proposed > Integer.MAX_VALUE
                        ? Integer.MAX_VALUE : (int) proposed;
                Slot[] expanded = new Slot[newCapacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = slots[i];
                }
                slots = expanded;
            }

            slots[size] = newSlot;
            size++;
            lastIssuedId = newId;
            revision = newRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expected revision must be nonnegative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            int index = -1;
            for (int i = 0; i < size; i++) {
                if (slots[i].reservationId == reservationId) {
                    index = i;
                    break;
                }
            }

            if (index < 0) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }

            long newRevision = revision + 1;
            MutationResult result =
                    new MutationResult(Status.SUCCESS, newRevision, reservationId);
            for (int i = index; i < size - 1; i++) {
                slots[i] = slots[i + 1];
            }
            size--;
            slots[size] = null;
            revision = newRevision;
            return result;
        }
    }
}
