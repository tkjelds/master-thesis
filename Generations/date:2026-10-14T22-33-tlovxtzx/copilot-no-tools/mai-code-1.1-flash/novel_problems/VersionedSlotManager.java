@VersionedSlotManager.ThreadSafe
public class VersionedSlotManager {
    @interface ThreadSafe {}

    private final Object mutex = new Object();
    private Slot[] slots = new Slot[0];
    private long revision = 0L;
    private long nextReservationId = 1L;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (mutex) {
            Slot[] copy = new Slot[slots.length];
            for (int i = 0; i < slots.length; i++) {
                Slot source = slots[i];
                copy[i] = new Slot(source.start, source.end, source.reservationId);
            }
            return new Snapshot(revision, copy);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        validateInterval(start, end);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new MutationResult(MutationResult.Status.STALE_REVISION, revision, 0L);
            }

            for (int i = 0; i < slots.length; i++) {
                Slot existing = slots[i];
                if (intervalsOverlap(start, end, existing.start, existing.end)) {
                    return new MutationResult(MutationResult.Status.CONFLICT, revision, 0L);
                }
            }

            if (nextReservationId == Long.MAX_VALUE) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }

            long assignedId = nextReservationId;
            nextReservationId += 1L;

            Slot[] updated = new Slot[slots.length + 1];
            System.arraycopy(slots, 0, updated, 0, slots.length);
            updated[slots.length] = new Slot(start, end, assignedId);
            slots = updated;
            revision += 1L;

            return new MutationResult(MutationResult.Status.SUCCESS, revision, assignedId);
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }

        synchronized (mutex) {
            if (expectedRevision != revision) {
                return new MutationResult(MutationResult.Status.STALE_REVISION, revision, 0L);
            }

            int index = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].reservationId == reservationId) {
                    index = i;
                    break;
                }
            }

            if (index < 0) {
                return new MutationResult(MutationResult.Status.NOT_FOUND, revision, 0L);
            }

            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision overflow");
            }

            Slot[] updated = new Slot[slots.length - 1];
            int writeIndex = 0;
            for (int i = 0; i < slots.length; i++) {
                if (i != index) {
                    updated[writeIndex] = slots[i];
                    writeIndex += 1;
                }
            }
            slots = updated;
            revision += 1L;

            return new MutationResult(MutationResult.Status.SUCCESS, revision, reservationId);
        }
    }

    private static void validateInterval(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must satisfy start < end");
        }
    }

    private static boolean intervalsOverlap(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart <= bEnd && bStart <= aEnd;
    }

    public static final class Slot {
        public final long start;
        public final long end;
        public final long reservationId;

        public Slot(long start, long end, long reservationId) {
            this.start = start;
            this.end = end;
            this.reservationId = reservationId;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final Slot[] slots;

        public Snapshot(long revision, Slot[] slots) {
            this.revision = revision;
            this.slots = slots;
        }
    }

    public static final class MutationResult {
        public enum Status {
            SUCCESS,
            STALE_REVISION,
            CONFLICT,
            NOT_FOUND
        }

        public final Status status;
        public final long revision;
        public final long reservationId;

        public MutationResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }
    }
}
