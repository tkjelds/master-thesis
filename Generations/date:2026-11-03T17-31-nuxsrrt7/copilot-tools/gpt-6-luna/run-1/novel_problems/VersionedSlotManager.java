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
        public final long reservationId;
        public final long start;
        public final long end;

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
        public final long revision;
        private final Slot[] slots;

        private Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }

        public long getRevision() {
            return revision;
        }

        public int getSlotCount() {
            return slots.length;
        }

        public Slot getSlotAt(int index) {
            Slot slot = slots[index];
            return new Slot(slot.reservationId, slot.start, slot.end);
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

    public static final class ReserveResult {
        public final ReserveStatus status;
        public final long revision;
        public final long reservationId;

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
        public final CancelStatus status;
        public final long revision;

        private CancelResult(CancelStatus status, long revision) {
            this.status = status;
            this.revision = revision;
        }

        public CancelStatus getStatus() {
            return status;
        }

        public long getRevision() {
            return revision;
        }
    }

    private final Object mutex = new Object();
    private Slot[] slots = new Slot[4];
    private int slotCount;
    private long revision;
    private long nextReservationId = 1;
    private boolean reservationIdsExhausted;

    public Snapshot getSnapshot() {
        synchronized (mutex) {
            Slot[] copy = new Slot[slotCount];
            for (int i = 0; i < slotCount; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copy);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < slotCount; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation IDs exhausted");
            }

            Slot newSlot = new Slot(nextReservationId, start, end);
            Slot[] target = slots;
            if (slotCount == slots.length) {
                if (slots.length > Integer.MAX_VALUE / 2) {
                    throw new IllegalStateException("slot capacity exhausted");
                }
                target = new Slot[slots.length * 2];
                for (int i = 0; i < slotCount; i++) {
                    target[i] = slots[i];
                }
            }

            target[slotCount] = newSlot;
            slots = target;
            slotCount++;
            revision++;
            long assignedId = nextReservationId;
            if (nextReservationId == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId++;
            }
            return new ReserveResult(ReserveStatus.SUCCESS, revision, assignedId);
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new CancelResult(CancelStatus.STALE_REVISION, revision);
            }

            int index = -1;
            for (int i = 0; i < slotCount; i++) {
                if (slots[i].reservationId == reservationId) {
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

            for (int i = index; i < slotCount - 1; i++) {
                slots[i] = slots[i + 1];
            }
            slots[slotCount - 1] = null;
            slotCount--;
            revision++;
            return new CancelResult(CancelStatus.SUCCESS, revision);
        }
    }
}
