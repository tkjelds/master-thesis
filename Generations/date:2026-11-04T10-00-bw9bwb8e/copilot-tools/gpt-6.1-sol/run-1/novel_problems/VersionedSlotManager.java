@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final long MAX_REVISION_OR_ID = 9223372036854775807L;

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

        // Zero on failure; otherwise the added or removed reservation's ID.
        public final long reservationId;

        private MutationResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }
    }

    private long revision;
    private long lastAssignedId;
    private Slot[] slots = new Slot[0];

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                copy[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copy);
        }
    }

    // Operations never wait for capacity or contents. Storage grows as needed;
    // allocation failures propagate without changing manager state.
    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == MAX_REVISION_OR_ID) {
                throw new IllegalStateException("Revision space exhausted");
            }
            if (lastAssignedId == MAX_REVISION_OR_ID) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }
            if (slots.length == 2147483647) {
                throw new IllegalStateException("Slot array capacity exhausted");
            }

            long newRevision = revision + 1;
            long newId = lastAssignedId + 1;
            Slot[] updated = new Slot[slots.length + 1];
            for (int i = 0; i < slots.length; i++) {
                updated[i] = slots[i];
            }
            updated[slots.length] = new Slot(newId, start, end);

            MutationResult result =
                    new MutationResult(Status.SUCCESS, newRevision, newId);

            slots = updated;
            lastAssignedId = newId;
            revision = newRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (this) {
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

            if (revision == MAX_REVISION_OR_ID) {
                throw new IllegalStateException("Revision space exhausted");
            }

            long newRevision = revision + 1;
            Slot[] updated = new Slot[slots.length - 1];
            for (int source = 0, target = 0; source < slots.length; source++) {
                if (source != index) {
                    updated[target++] = slots[source];
                }
            }

            MutationResult result =
                    new MutationResult(Status.SUCCESS, newRevision, reservationId);

            slots = updated;
            revision = newRevision;
            return result;
        }
    }
}
