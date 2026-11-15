@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

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

    public static final class MutationResult {
        public final Status status;
        public final long revision;

        // Positive only for a successful reserve; zero for all other results.
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

    private static final class Monitor {
    }

    private static final class Node {
        private final Slot slot;
        private Node next;

        private Node(Slot slot, Node next) {
            this.slot = slot;
            this.next = next;
        }
    }

    private static final long MAX_COUNTER = 9223372036854775807L;
    private static final int MAX_SLOTS = 2147483647;

    private final Monitor monitor = new Monitor();
    private Node head;
    private int size;
    private long revision;
    private long lastAssignedId;

    public VersionedSlotManager() {
    }

    public Snapshot getSnapshot() {
        synchronized (monitor) {
            Slot[] copies = new Slot[size];
            Node current = head;
            for (int i = 0; i < copies.length; i++) {
                Slot slot = current.slot;
                copies[i] = new Slot(slot.reservationId, slot.start, slot.end);
                current = current.next;
            }
            return new Snapshot(revision, copies);
        }
    }

    /*
     * Operations never wait for slots to appear or disappear. They acquire only
     * the private monitor. Counter or representational exhaustion throws rather
     * than waiting; allocation failures propagate without changing state.
     */
    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (Node current = head; current != null; current = current.next) {
                Slot slot = current.slot;
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == MAX_COUNTER) {
                throw new IllegalStateException("Revision exhausted");
            }
            if (lastAssignedId == MAX_COUNTER) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }
            if (size == MAX_SLOTS) {
                throw new IllegalStateException("Snapshot capacity exhausted");
            }

            long nextRevision = revision + 1;
            long nextId = lastAssignedId + 1;

            // Complete allocations before committing any state changes.
            Node addition = new Node(new Slot(nextId, start, end), head);
            MutationResult result =
                    new MutationResult(Status.SUCCESS, nextRevision, nextId);

            head = addition;
            size++;
            lastAssignedId = nextId;
            revision = nextRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }

        synchronized (monitor) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            Node previous = null;
            Node current = head;
            while (current != null && current.slot.reservationId != reservationId) {
                previous = current;
                current = current.next;
            }

            if (current == null) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }

            if (revision == MAX_COUNTER) {
                throw new IllegalStateException("Revision exhausted");
            }

            long nextRevision = revision + 1;
            MutationResult result =
                    new MutationResult(Status.SUCCESS, nextRevision, 0);

            if (previous == null) {
                head = current.next;
            } else {
                previous.next = current.next;
            }
            size--;
            revision = nextRevision;
            return result;
        }
    }
}
