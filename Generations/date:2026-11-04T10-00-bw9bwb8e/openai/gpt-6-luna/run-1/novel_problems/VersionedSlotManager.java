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
        private final long revision;
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

        public Slot getSlot(int index) {
            return slots[index];
        }

        public Slot[] getSlots() {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                copy[i] = slots[i];
            }
            return copy;
        }
    }

    public static final class MutationResult {
        public final MutationStatus status;
        public final long revision;
        public final long reservationId;

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

    private Slot[] slots = new Slot[0];
    private int slotCount;
    private long revision;
    private long nextReservationId = 1;
    private boolean reservationIdsExhausted;

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] copy = new Slot[slotCount];
            for (int i = 0; i < slotCount; i++) {
                copy[i] = slots[i];
            }
            return new Snapshot(revision, copy);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(
                        MutationStatus.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < slotCount; i++) {
                Slot existing = slots[i];
                if (start <= existing.end && existing.start <= end) {
                    return new MutationResult(
                            MutationStatus.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }
            if (reservationIdsExhausted) {
                throw new IllegalStateException("reservation IDs exhausted");
            }

            long id = nextReservationId;
            long newRevision = revision + 1;
            Slot newSlot = new Slot(id, start, end);
            MutationResult result =
                    new MutationResult(MutationStatus.SUCCESS, newRevision, id);

            ensureCapacityForOneMore();
            slots[slotCount] = newSlot;
            slotCount++;
            revision = newRevision;

            if (id == Long.MAX_VALUE) {
                reservationIdsExhausted = true;
            } else {
                nextReservationId = id + 1;
            }

            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(
                        MutationStatus.STALE_REVISION, revision, 0);
            }

            int index = -1;
            for (int i = 0; i < slotCount; i++) {
                if (slots[i].reservationId == reservationId) {
                    index = i;
                    break;
                }
            }

            if (index < 0) {
                return new MutationResult(
                        MutationStatus.NOT_FOUND, revision, 0);
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }

            long newRevision = revision + 1;
            MutationResult result =
                    new MutationResult(MutationStatus.SUCCESS, newRevision, reservationId);

            for (int i = index; i < slotCount - 1; i++) {
                slots[i] = slots[i + 1];
            }
            slots[slotCount - 1] = null;
            slotCount--;
            revision = newRevision;

            return result;
        }
    }

    private void ensureCapacityForOneMore() {
        if (slotCount < slots.length) {
            return;
        }

        int oldCapacity = slots.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 4;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        if (newCapacity <= slotCount) {
            throw new IllegalStateException("too many slots");
        }

        Slot[] expanded = new Slot[newCapacity];
        for (int i = 0; i < slotCount; i++) {
            expanded[i] = slots[i];
        }
        slots = expanded;
    }
}
