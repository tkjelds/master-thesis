@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Slot[] slots = new Slot[0];
    private int size;
    private long revision;
    private long lastReservationId;

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
            this.slots = copySlots(slots);
        }

        public long getRevision() {
            return revision;
        }

        public Slot[] getSlots() {
            return copySlots(slots);
        }

        public int size() {
            return slots.length;
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

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] captured = new Slot[size];
            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                captured[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, captured);
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

            for (int i = 0; i < size; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && end >= slot.start) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE || lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("revision or reservation ID exhausted");
            }

            ensureCapacity(size + 1);
            long reservationId = lastReservationId + 1;
            slots[size] = new Slot(reservationId, start, end);
            size++;
            lastReservationId = reservationId;
            revision++;

            return new MutationResult(Status.SUCCESS, revision, reservationId);
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

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision exhausted");
            }

            for (int i = index; i < size - 1; i++) {
                slots[i] = slots[i + 1];
            }
            slots[size - 1] = null;
            size--;
            revision++;

            return new MutationResult(Status.SUCCESS, revision, reservationId);
        }
    }

    private void ensureCapacity(int required) {
        if (required <= slots.length) {
            return;
        }
        if (slots.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("too many reservations");
        }

        int newCapacity = slots.length == 0 ? 1 : slots.length;
        while (newCapacity < required) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = Integer.MAX_VALUE;
            } else {
                newCapacity *= 2;
            }
        }

        Slot[] expanded = new Slot[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = slots[i];
        }
        slots = expanded;
    }

    private static Slot[] copySlots(Slot[] source) {
        Slot[] copy = new Slot[source.length];
        for (int i = 0; i < source.length; i++) {
            Slot slot = source[i];
            copy[i] = new Slot(slot.reservationId, slot.start, slot.end);
        }
        return copy;
    }
}
