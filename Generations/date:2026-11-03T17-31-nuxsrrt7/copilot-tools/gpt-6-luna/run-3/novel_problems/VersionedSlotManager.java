@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    public enum MutationStatus {
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
        private final MutationStatus status;
        private final long revision;
        private final long reservationId;

        private MutationResult(MutationStatus status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }

        public MutationStatus getStatus() {
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
    private long nextReservationId = 1L;
    private boolean reservationIdsExhausted;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            return new Snapshot(revision, slots.clone());
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, 0L);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot existing = slots[i];
                if (start <= existing.end && existing.start <= end) {
                    return new MutationResult(MutationStatus.CONFLICT, revision, 0L);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation ID space exhausted");
            }

            long reservationId = nextReservationId;
            Slot[] updatedSlots = new Slot[slots.length + 1];
            System.arraycopy(slots, 0, updatedSlots, 0, slots.length);
            updatedSlots[slots.length] = new Slot(reservationId, start, end);

            slots = updatedSlots;
            revision++;
            if (reservationId == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = reservationId + 1L;
            }
            return new MutationResult(MutationStatus.SUCCESS, revision, reservationId);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, 0L);
            }

            int slotIndex = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].reservationId == reservationId) {
                    slotIndex = i;
                    break;
                }
            }
            if (slotIndex < 0) {
                return new MutationResult(MutationStatus.NOT_FOUND, revision, 0L);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }

            Slot[] updatedSlots = new Slot[slots.length - 1];
            System.arraycopy(slots, 0, updatedSlots, 0, slotIndex);
            System.arraycopy(slots, slotIndex + 1, updatedSlots, slotIndex,
                    slots.length - slotIndex - 1);

            slots = updatedSlots;
            revision++;
            return new MutationResult(MutationStatus.SUCCESS, revision, reservationId);
        }
    }
}
