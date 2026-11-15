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
        public final long id;
        public final long start;
        public final long end;

        private Slot(long id, long start, long end) {
            this.id = id;
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

        // Zero on failure; otherwise the reserved or cancelled ID.
        public final long reservationId;

        private MutationResult(Status status, long revision, long reservationId) {
            this.status = status;
            this.revision = revision;
            this.reservationId = reservationId;
        }
    }

    private static final class Node {
        private final Slot slot;
        private Node next;

        private Node(Slot slot, Node next) {
            this.slot = slot;
            this.next = next;
        }
    }

    private static final long MAX_VALUE = 9223372036854775807L;
    private static final int MAX_SLOTS = 2147483647;

    private final Object lock = new Object();
    private Node head;
    private int size;
    private long revision;
    private long lastAssignedId;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            Slot[] slots = new Slot[size];
            Node current = head;
            for (int i = 0; i < size; i++) {
                Slot slot = current.slot;
                slots[i] = new Slot(slot.id, slot.start, slot.end);
                current = current.next;
            }
            return new Snapshot(revision, slots);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid interval or expected revision");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (Node current = head; current != null; current = current.next) {
                Slot slot = current.slot;
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == MAX_VALUE || lastAssignedId == MAX_VALUE) {
                throw new IllegalStateException("Revision or reservation ID exhausted");
            }
            // Snapshots use arrays, so the number of retained slots is bounded.
            if (size == MAX_SLOTS) {
                throw new IllegalStateException("Slot capacity exhausted");
            }

            long id = lastAssignedId + 1;
            long newRevision = revision + 1;
            Node newHead = new Node(new Slot(id, start, end), head);
            MutationResult result = new MutationResult(Status.SUCCESS, newRevision, id);

            head = newHead;
            size++;
            lastAssignedId = id;
            revision = newRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Invalid expected revision");
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            Node previous = null;
            Node current = head;
            while (current != null && current.slot.id != reservationId) {
                previous = current;
                current = current.next;
            }

            if (current == null) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }
            if (revision == MAX_VALUE) {
                throw new IllegalStateException("Revision exhausted");
            }

            long newRevision = revision + 1;
            MutationResult result =
                    new MutationResult(Status.SUCCESS, newRevision, reservationId);

            if (previous == null) {
                head = current.next;
            } else {
                previous.next = current.next;
            }
            size--;
            revision = newRevision;
            return result;
        }
    }
}
