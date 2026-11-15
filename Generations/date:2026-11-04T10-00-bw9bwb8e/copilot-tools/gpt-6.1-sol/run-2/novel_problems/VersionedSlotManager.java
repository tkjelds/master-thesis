@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final long MAX_COUNTER = 9223372036854775807L;

    private static final class Monitor {
    }

    private final Monitor monitor = new Monitor();
    private Slot[] slots = new Slot[0];
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

        private Slot(long reservationId, long start, long end) {
            this.reservationId = reservationId;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Snapshot {
        public final long revision;
        private final Slot[] slots;

        private Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
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
        public final Status status;
        public final long revision;

        // Zero on failure; otherwise the created or canceled reservation's ID.
        public final long reservationId;

        private MutationResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }
    }

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (monitor) {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copy);
        }
    }

    /**
     * Never waits for capacity or for conflicting reservations to disappear.
     * Storage grows as needed; allocation failure propagates without mutation.
     */
    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        checkExpectedRevision(expectedRevision);

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == MAX_COUNTER) {
                throw new IllegalStateException("Revision exhausted");
            }
            if (lastReservationId == MAX_COUNTER) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }
            if (slots.length == 2147483647) {
                throw new IllegalStateException("Reservation storage exhausted");
            }

            long nextRevision = revision + 1;
            long nextId = lastReservationId + 1;
            Slot[] nextSlots = new Slot[slots.length + 1];
            for (int i = 0; i < slots.length; i++) {
                nextSlots[i] = slots[i];
            }
            nextSlots[slots.length] = new Slot(nextId, start, end);

            MutationResult result =
                    new MutationResult(Status.SUCCESS, nextRevision, nextId);

            slots = nextSlots;
            lastReservationId = nextId;
            revision = nextRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        checkExpectedRevision(expectedRevision);

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            int index = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].reservationId == reservationId) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }
            if (revision == MAX_COUNTER) {
                throw new IllegalStateException("Revision exhausted");
            }

            long nextRevision = revision + 1;
            Slot[] nextSlots = new Slot[slots.length - 1];
            for (int source = 0, target = 0; source < slots.length; source++) {
                if (source != index) {
                    nextSlots[target++] = slots[source];
                }
            }

            MutationResult result =
                    new MutationResult(Status.SUCCESS, nextRevision, reservationId);

            slots = nextSlots;
            revision = nextRevision;
            return result;
        }
    }

    private static void checkExpectedRevision(long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }
    }
}
