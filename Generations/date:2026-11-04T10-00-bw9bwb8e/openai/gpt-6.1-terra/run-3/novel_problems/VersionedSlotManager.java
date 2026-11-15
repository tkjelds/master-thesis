@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    public enum Status {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }

    /** An immutable, detached reservation. */
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

    /** An immutable view of one revision of the manager. */
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

        /** Returns a new array; its Slot elements are immutable. */
        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                copy[i] = slots[i];
            }
            return copy;
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

        /**
         * Returns the assigned ID for a successful reserve, or zero otherwise.
         */
        public long getReservationId() {
            return reservationId;
        }
    }

    private static final long MAX_LONG = 9223372036854775807L;
    private static final int MAX_ARRAY_LENGTH = 2147483647;

    private final Object lock = new Object();
    private Slot[] slots = new Slot[8];
    private int size;
    private long revision;
    private long lastAssignedId;

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
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException();
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && end >= slot.start) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == MAX_LONG || lastAssignedId == MAX_LONG) {
                throw new IllegalStateException("Revision or reservation ID exhausted");
            }

            /*
             * Prepare all allocations before changing state. The structure
             * grows as needed and does not wait for capacity.
             */
            Slot[] destination = slots;
            if (size == slots.length) {
                if (size == MAX_ARRAY_LENGTH) {
                    throw new IllegalStateException("Reservation capacity exhausted");
                }
                long doubled = (long) slots.length * 2;
                int newLength = doubled > MAX_ARRAY_LENGTH
                        ? MAX_ARRAY_LENGTH : (int) doubled;
                destination = new Slot[newLength];
                for (int i = 0; i < size; i++) {
                    destination[i] = slots[i];
                }
            }

            long newId = lastAssignedId + 1;
            long newRevision = revision + 1;
            Slot newSlot = new Slot(newId, start, end);
            MutationResult result = new MutationResult(
                    Status.SUCCESS, newRevision, newId);

            destination[size] = newSlot;
            slots = destination;
            size++;
            lastAssignedId = newId;
            revision = newRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException();
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

            if (revision == MAX_LONG) {
                throw new IllegalStateException("Revision exhausted");
            }

            long newRevision = revision + 1;
            MutationResult result = new MutationResult(
                    Status.SUCCESS, newRevision, 0);

            for (int i = index; i < size - 1; i++) {
                slots[i] = slots[i + 1];
            }
            slots[size - 1] = null;
            size--;
            revision = newRevision;
            return result;
        }
    }
}
