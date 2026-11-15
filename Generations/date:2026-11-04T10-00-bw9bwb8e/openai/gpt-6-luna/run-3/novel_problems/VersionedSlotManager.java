@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    private final Object mutex = new Object();

    private Slot[] slots = new Slot[0];
    private long revision = 0L;
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted = false;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (mutex) {
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

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new ReserveResult(ReserveStatus.STALE_REVISION, revision, 0L);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot existing = slots[i];
                if (start <= existing.end && existing.start <= end) {
                    return new ReserveResult(ReserveStatus.CONFLICT, revision, 0L);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation ID space exhausted");
            }
            if (slots.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("slot capacity exhausted");
            }

            long assignedId = nextReservationId;
            Slot newSlot = new Slot(assignedId, start, end);
            Slot[] newSlots = new Slot[slots.length + 1];
            for (int i = 0; i < slots.length; i++) {
                newSlots[i] = slots[i];
            }
            newSlots[slots.length] = newSlot;

            long newRevision = revision + 1L;
            slots = newSlots;
            revision = newRevision;
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
                return new CancelResult(CancelStatus.STALE_REVISION, revision);
            }

            int foundIndex = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].id == reservationId) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex < 0) {
                return new CancelResult(CancelStatus.NOT_FOUND, revision);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }

            Slot[] newSlots = new Slot[slots.length - 1];
            int destination = 0;
            for (int i = 0; i < slots.length; i++) {
                if (i != foundIndex) {
                    newSlots[destination++] = slots[i];
                }
            }

            slots = newSlots;
            revision = revision + 1L;
            return new CancelResult(CancelStatus.SUCCESS, revision);
        }
    }

    public static final class Slot {
        public final long id;
        public final long start;
        public final long end;

        public Slot(long id, long start, long end) {
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
    }

    public static final class Snapshot {
        public final long revision;
        private final Slot[] slots;

        private Snapshot(long revision, Slot[] sourceSlots) {
            this.revision = revision;
            this.slots = copySlots(sourceSlots);
        }

        public long getRevision() {
            return revision;
        }

        public int size() {
            return slots.length;
        }

        public Slot getSlot(int index) {
            Slot slot = slots[index];
            return new Slot(slot.id, slot.start, slot.end);
        }

        public Slot[] getSlots() {
            return copySlots(slots);
        }

        private static Slot[] copySlots(Slot[] source) {
            Slot[] copy = new Slot[source.length];
            for (int i = 0; i < source.length; i++) {
                Slot slot = source[i];
                copy[i] = new Slot(slot.id, slot.start, slot.end);
            }
            return copy;
        }
    }

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
}
