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
        public final long reservationId;
        public final long start;
        public final long end;

        public Slot(long reservationId, long start, long end) {
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
        public final long revision;
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

        public Slot getSlot(int index) {
            return slots[index];
        }

        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return copy;
        }
    }

    public static final class MutationResult {
        public final Status status;
        public final long revision;
        public final long reservationId;

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

        public long getReservationId() {
            return reservationId;
        }
    }

    private final Object lock = new Object();
    private Slot[] slots = new Slot[0];
    private long revision;
    private long lastReservationId;
    private boolean reservationIdsExhausted;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
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
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation IDs exhausted");
            }
            if (slots.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("slot capacity exhausted");
            }

            long id = lastReservationId + 1;
            Slot newSlot = new Slot(id, start, end);
            Slot[] updated = new Slot[slots.length + 1];
            for (int i = 0; i < slots.length; i++) {
                updated[i] = slots[i];
            }
            updated[slots.length] = newSlot;

            slots = updated;
            lastReservationId = id;
            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            }
            revision++;
            return new MutationResult(Status.SUCCESS, revision, id);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            int found = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].reservationId == reservationId) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }

            Slot[] updated = new Slot[slots.length - 1];
            for (int i = 0, j = 0; i < slots.length; i++) {
                if (i != found) {
                    updated[j++] = slots[i];
                }
            }

            slots = updated;
            revision++;
            return new MutationResult(Status.SUCCESS, revision, reservationId);
        }
    }
}
