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
            return slots.clone();
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

        /**
         * Returns the observed revision for a failed operation, or the resulting
         * revision for a successful operation.
         */
        public long getRevision() {
            return revision;
        }

        public long getObservedRevision() {
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

        /**
         * Returns the observed revision for a failed operation, or the resulting
         * revision for a successful operation.
         */
        public long getRevision() {
            return revision;
        }

        public long getObservedRevision() {
            return revision;
        }

        public long getReservationId() {
            return reservationId;
        }
    }

    private final Object mutex = new Object();
    private Slot[] slots = new Slot[8];
    private int slotCount;
    private long revision;
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (mutex) {
            Slot[] copy = new Slot[slotCount];
            for (int i = 0; i < slotCount; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(
                        slot.reservationId,
                        slot.start,
                        slot.end);
            }
            return new Snapshot(revision, copy);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0L);
            }

            for (int i = 0; i < slotCount; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0L);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation ID space exhausted");
            }

            long assignedId = nextReservationId;
            Slot newSlot = new Slot(assignedId, start, end);

            if (slotCount == slots.length) {
                if (slots.length == Integer.MAX_VALUE) {
                    throw new IllegalStateException("slot capacity exhausted");
                }
                int newLength = slots.length > Integer.MAX_VALUE / 2
                        ? Integer.MAX_VALUE
                        : slots.length * 2;
                if (newLength <= slots.length) {
                    throw new IllegalStateException("slot capacity exhausted");
                }
                Slot[] expanded = new Slot[newLength];
                for (int i = 0; i < slotCount; i++) {
                    expanded[i] = slots[i];
                }
                slots = expanded;
            }

            slots[slotCount] = newSlot;
            slotCount++;
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

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new CancelResult(CancelStatus.STALE_REVISION, revision, reservationId);
            }

            int foundIndex = -1;
            for (int i = 0; i < slotCount; i++) {
                if (slots[i].reservationId == reservationId) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex < 0) {
                return new CancelResult(CancelStatus.NOT_FOUND, revision, reservationId);
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }

            for (int i = foundIndex; i < slotCount - 1; i++) {
                slots[i] = slots[i + 1];
            }
            slots[slotCount - 1] = null;
            slotCount--;
            revision++;

            return new CancelResult(CancelStatus.SUCCESS, revision, reservationId);
        }
    }
}
