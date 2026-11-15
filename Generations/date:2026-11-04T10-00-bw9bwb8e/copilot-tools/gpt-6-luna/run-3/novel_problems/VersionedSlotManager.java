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

    private Slot[] reservations = new Slot[0];
    private long revision;
    private long lastReservationId;

    public Snapshot getSnapshot() {
        synchronized (this) {
            Slot[] copiedSlots = new Slot[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                Slot slot = reservations[i];
                copiedSlots[i] = new Slot(slot.reservationId, slot.start, slot.end);
            }
            return new Snapshot(revision, copiedSlots);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, 0);
            }

            for (Slot slot : reservations) {
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(MutationStatus.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }
            if (lastReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("reservation ID space exhausted");
            }
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("reservation capacity exhausted");
            }

            long newRevision = revision + 1;
            long newReservationId = lastReservationId + 1;
            Slot[] updatedReservations = new Slot[reservations.length + 1];
            for (int i = 0; i < reservations.length; i++) {
                updatedReservations[i] = reservations[i];
            }
            updatedReservations[reservations.length] =
                    new Slot(newReservationId, start, end);
            MutationResult result =
                    new MutationResult(MutationStatus.SUCCESS, newRevision, newReservationId);

            reservations = updatedReservations;
            lastReservationId = newReservationId;
            revision = newRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }

        synchronized (this) {
            if (expectedRevision != revision) {
                return new MutationResult(MutationStatus.STALE_REVISION, revision, 0);
            }

            int foundIndex = -1;
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].reservationId == reservationId) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex == -1) {
                return new MutationResult(MutationStatus.NOT_FOUND, revision, 0);
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("revision space exhausted");
            }

            long newRevision = revision + 1;
            Slot[] updatedReservations = new Slot[reservations.length - 1];
            for (int source = 0, target = 0; source < reservations.length; source++) {
                if (source != foundIndex) {
                    updatedReservations[target++] = reservations[source];
                }
            }
            MutationResult result =
                    new MutationResult(MutationStatus.SUCCESS, newRevision, reservationId);

            reservations = updatedReservations;
            revision = newRevision;
            return result;
        }
    }
}
