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

    private static final class SnapshotNode {
        private final Slot slot;
        private final SnapshotNode next;

        private SnapshotNode(Slot slot, SnapshotNode next) {
            this.slot = slot;
            this.next = next;
        }
    }

    public static final class Snapshot {
        private final long revision;
        private final long slotCount;
        private final SnapshotNode head;

        private Snapshot(long revision, long slotCount, SnapshotNode head) {
            this.revision = revision;
            this.slotCount = slotCount;
            this.head = head;
        }

        public long getRevision() {
            return revision;
        }

        public long getSlotCount() {
            return slotCount;
        }

        public Slot getSlot(long index) {
            if (index < 0 || index >= slotCount) {
                throw new IndexOutOfBoundsException();
            }
            SnapshotNode node = head;
            for (long i = 0; i < index; i++) {
                node = node.next;
            }
            return node.slot;
        }

        // Returns a new array on every call. Slots themselves are immutable.
        public Slot[] getSlots() {
            if (slotCount > Integer.MAX_VALUE) {
                throw new IllegalStateException("Snapshot is too large for an array");
            }
            Slot[] slots = new Slot[(int) slotCount];
            SnapshotNode node = head;
            for (int i = 0; i < slots.length; i++) {
                slots[i] = node.slot;
                node = node.next;
            }
            return slots;
        }
    }

    public static final class MutationResult {
        public final Status status;
        public final long revision;
        // Zero indicates that this result did not assign a reservation ID.
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

    private static final class Node {
        private final Slot slot;
        private Node next;

        private Node(Slot slot, Node next) {
            this.slot = slot;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node head;
    private long revision;
    private long lastAssignedId;
    private long slotCount;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            SnapshotNode copiedHead = null;
            for (Node node = head; node != null; node = node.next) {
                Slot slot = node.slot;
                Slot copy = new Slot(slot.reservationId, slot.start, slot.end);
                copiedHead = new SnapshotNode(copy, copiedHead);
            }
            return new Snapshot(revision, slotCount, copiedHead);
        }
    }

    public MutationResult reserve(long start, long end, long expectedRevision) {
        if (start >= end || expectedRevision < 0) {
            throw new IllegalArgumentException();
        }

        synchronized (lock) {
            if (expectedRevision != revision) {
                return new MutationResult(Status.STALE_REVISION, revision, 0);
            }

            for (Node node = head; node != null; node = node.next) {
                Slot slot = node.slot;
                if (start <= slot.end && slot.start <= end) {
                    return new MutationResult(Status.CONFLICT, revision, 0);
                }
            }

            if (revision == Long.MAX_VALUE || lastAssignedId == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision or reservation ID exhausted");
            }

            long id = lastAssignedId + 1;
            long newRevision = revision + 1;
            Slot slot = new Slot(id, start, end);
            Node newHead = new Node(slot, head);
            MutationResult result = new MutationResult(Status.SUCCESS, newRevision, id);

            head = newHead;
            lastAssignedId = id;
            slotCount++;
            revision = newRevision;
            return result;
        }
    }

    public MutationResult cancel(long reservationId, long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException();
        }

        synchronized (lock) {
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
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Revision exhausted");
            }

            long newRevision = revision + 1;
            MutationResult result = new MutationResult(Status.SUCCESS, newRevision, 0);

            if (previous == null) {
                head = current.next;
            } else {
                previous.next = current.next;
            }
            slotCount--;
            revision = newRevision;
            return result;
        }
    }
}
