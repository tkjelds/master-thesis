@VersionedSlotManager.ThreadSafe
class VersionedSlotManager {
    @interface ThreadSafe {}

    private final Slot[] slots;
    private long revision;

    public VersionedSlotManager(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        slots = new Slot[capacity];
        revision = 0;
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] snapshotSlots = new Slot[slots.length];
            System.arraycopy(slots, 0, snapshotSlots, 0, slots.length);
            return new Snapshot(snapshotSlots, revision);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        synchronized (this) {
            if (start >= end) throw new IllegalArgumentException("Invalid interval");
            if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision must be non-negative");
            if (revision != expectedRevision) return MutationResult.STALE_REVISION;

            for (Slot slot : slots) {
                if (slot != null && (start <= slot.end && end >= slot.start)) {
                    return MutationResult.CONFLICT;
                }
            }

            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == null) {
                    slots[i] = new Slot(start, end);
                    return new MutationResult(reservationId(slots), revision + 1);
                }
            }

            // If we reached here, the array might be full or not properly synchronized
            throw new IllegalStateException("Cannot reserve slot due to capacity issues");
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        synchronized (this) {
            if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision must be non-negative");
            if (revision != expectedRevision) return MutationResult.STALE_REVISION;

            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (slot != null && reservationId(slot) == reservationId) {
                    slots[i] = null;
                    return new MutationResult(MutationType.SUCCESS, revision + 1);
                }
            }

            return MutationResult.NOT_FOUND;
        }
    }

    private Slot[] copySlots() {
        synchronized (this) {
            Slot[] snapshotSlots = new Slot[slots.length];
            System.arraycopy(slots, 0, snapshotSlots, 0, slots.length);
            return snapshotSlots;
        }
    }

    private static long reservationId(Slot slot) {
        // This is a simple example of a reservation ID generator
        return System.identityHashCode(slot);
    }

    public static class Snapshot {
        private final Slot[] slots;
        private final long revision;

        public Snapshot(Slot[] slots, long revision) {
            this.slots = slots;
            this.revision = revision;
        }

        public Slot[] getSlots() {
            return slots;
        }

        public long getRevision() {
            return revision;
        }
    }

    public static class MutationResult {
        private final MutationType type;
        private final long revision;
        private final long reservationId;

        public MutationResult(MutationType type, long revision) {
            this.type = type;
            this.revision = revision;
            this.reservationId = -1; // reservationId is not applicable for SUCCESS or STALE_REVISION
        }

        public MutationResult(long reservationId, long revision) {
            this(MutationType.SUCCESS, revision);
            this.reservationId = reservationId;
        }

        public MutationType getType() {
            return type;
        }

        public long getRevision() {
            return revision;
        }

        public long getReservationId() {
            return reservationId;
        }
    }

    private static class Slot {
        final long start;
        final long end;

        public Slot(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    public static enum MutationType {
        SUCCESS,
        STALE_REVISION,
        CONFLICT,
        NOT_FOUND
    }
}
