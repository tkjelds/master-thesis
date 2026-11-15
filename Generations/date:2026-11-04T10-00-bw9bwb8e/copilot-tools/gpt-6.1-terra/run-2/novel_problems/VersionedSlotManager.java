@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    public enum ReserveStatus {
        SUCCESS, STALE_REVISION, CONFLICT
    }

    public enum CancelStatus {
        SUCCESS, STALE_REVISION, NOT_FOUND
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
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                copy[i] = slots[i];
            }
            return copy;
        }
    }

    public static final class ReserveResult {
        private final ReserveStatus status;
        private final long revision;
        private final long reservationId;

        private ReserveResult(
                ReserveStatus status, long revision, long reservationId) {
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

        // Zero indicates that no reservation was assigned.
        public long getReservationId() {
            return reservationId;
        }
    }

    public static final class CancelResult {
        private final CancelStatus status;
        private final long revision;

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

    private static final long MAX_LONG = 9223372036854775807L;
    private static final int MAX_SLOTS = 2147483647;

    private final Object lock = new Object();
    private Slot[] slots = new Slot[8];
    private int size;
    private long revision;
    private long lastReservationId;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] copy = new Slot[size];
            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(
                        slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copy);
        }
    }

    public ReserveResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expected revision must be nonnegative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0);
                }
            }

            if (revision == MAX_LONG || lastReservationId == MAX_LONG) {
                throw new IllegalStateException("revision or reservation ID exhausted");
            }
            if (size == MAX_SLOTS) {
                throw new IllegalStateException("slot capacity exhausted");
            }

            long newId = lastReservationId + 1;
            long newRevision = revision + 1;
            Slot newSlot = new Slot(newId, start, end);
            ReserveResult result =
                    new ReserveResult(ReserveStatus.SUCCESS, newRevision, newId);

            if (size == slots.length) {
                long doubled = (long) slots.length * 2;
                int newCapacity = doubled > MAX_SLOTS
                        ? MAX_SLOTS : (int) doubled;
                Slot[] expanded = new Slot[newCapacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = slots[i];
                }
                slots = expanded;
            }

            slots[size] = newSlot;
            size++;
            lastReservationId = newId;
            revision = newRevision;
            return result;
        }
    }

    public CancelResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expected revision must be nonnegative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new CancelResult(CancelStatus.STALE_REVISION, revision);
            }

            int index = -1;
            for (int i = 0; i < size; i++) {
                if (slots[i].reservationId == reservationId) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return new CancelResult(CancelStatus.NOT_FOUND, revision);
            }
            if (revision == MAX_LONG) {
                throw new IllegalStateException("revision exhausted");
            }

            long newRevision = revision + 1;
            CancelResult result = new CancelResult(CancelStatus.SUCCESS, newRevision);
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
