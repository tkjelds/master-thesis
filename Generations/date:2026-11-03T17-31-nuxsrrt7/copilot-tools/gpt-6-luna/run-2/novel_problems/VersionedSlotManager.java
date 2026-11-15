@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private Slot[] slots = new Slot[0];
    private long revision;
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;

    public Snapshot getSnapshot() {
        synchronized (monitor) {
            Slot[] copiedSlots = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                copiedSlots[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copiedSlots);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0L);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0L);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation ID space exhausted");
            }

            Slot[] updatedSlots = appendSlot(slots, new Slot(nextReservationId, start, end));
            long assignedId = nextReservationId;
            if (assignedId == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = assignedId + 1L;
            }
            slots = updatedSlots;
            revision++;
            return new MutationResult(Status.SUCCESS, revision, assignedId);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0L);
            }

            int foundIndex = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].reservationId == reservationId) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex < 0) {
                return new MutationResult(Status.NOT_FOUND, revision, 0L);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }

            Slot[] updatedSlots = new Slot[slots.length - 1];
            for (int source = 0, target = 0; source < slots.length; source++) {
                if (source != foundIndex) {
                    updatedSlots[target++] = slots[source];
                }
            }
            slots = updatedSlots;
            revision++;
            return new MutationResult(Status.SUCCESS, revision, reservationId);
        }
    }

    private static Slot[] appendSlot(Slot[] current, Slot newSlot) {
        if (current.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("slot capacity exhausted");
        }
        int newLength = current.length + 1;
        Slot[] updated = new Slot[newLength];
        for (int i = 0; i < current.length; i++) {
            updated[i] = current[i];
        }
        updated[current.length] = newSlot;
        return updated;
    }

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

        public int size() {
            return slots.length;
        }

        public Slot getSlot(int index) {
            return slots[index];
        }

        public Slot[] getSlots() {
            Slot[] copiedSlots = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                copiedSlots[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return copiedSlots;
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

        public long getReservationId() {
            return reservationId;
        }
    }
}
