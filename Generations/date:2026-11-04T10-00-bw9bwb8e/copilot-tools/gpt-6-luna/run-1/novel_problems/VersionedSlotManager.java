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

        private Snapshot(long revision, Slot[] source) {
            this.revision = revision;
            this.slots = copySlots(source);
        }

        public long getRevision() {
            return revision;
        }

        public int getSlotCount() {
            return slots.length;
        }

        public Slot getSlot(int index) {
            if (index < 0 || index >= slots.length) {
                throw new IndexOutOfBoundsException();
            }
            return copySlot(slots[index]);
        }

        public Slot[] getSlots() {
            return copySlots(slots);
        }
    }

    public static final class ReserveResult {
        private final ReserveStatus status;
        private final long revision;
        private final long reservationId;

        private ReserveResult(ReserveStatus status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public ReserveStatus getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }

        public long getReservationId() {
            return reservationId;
        }
    }

    public static final class CancelResult {
        private final CancelStatus status;
        private final long revision;
        private final long reservationId;

        private CancelResult(CancelStatus status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public CancelStatus getStatus() {
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
    private long revision = 0L;
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            return new Snapshot(revision, slots);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0L);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0L);
                }
            }

            if (revision == Long.MAX_VALUE || reservationIdsExhausted) {
                throw new IllegalStateException("Revision or reservation ID space exhausted");
            }
            if (slots.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many reservations");
            }

            long assignedId = nextReservationId;
            Slot[] updatedSlots = new Slot[slots.length + 1];
            for (int i = 0; i < slots.length; i++) {
                updatedSlots[i] = slots[i];
            }
            updatedSlots[slots.length] = new Slot(assignedId, start, end);

            slots = updatedSlots;
            revision++;
            if (assignedId == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = assignedId + 1L;
            }

            return new ReserveResult(ReserveStatus.SUCCESS, revision, assignedId);
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new CancelResult(CancelStatus.STALE_REVISION, revision, reservationId);
            }

            int foundIndex = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].reservationId == reservationId) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex < 0) {
                return new CancelResult(CancelStatus.NOT_FOUND, revision, reservationId);
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision space exhausted");
            }

            Slot[] updatedSlots = new Slot[slots.length - 1];
            for (int source = 0, target = 0; source < slots.length; source++) {
                if (source != foundIndex) {
                    updatedSlots[target++] = slots[source];
                }
            }

            slots = updatedSlots;
            revision++;
            return new CancelResult(CancelStatus.SUCCESS, revision, reservationId);
        }
    }

    private static Slot[] copySlots(Slot[] source) {
        Slot[] copy = new Slot[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = copySlot(source[i]);
        }
        return copy;
    }

    private static Slot copySlot(Slot slot) {
        return new Slot(slot.reservationId, slot.start, slot.end);
    }
}
