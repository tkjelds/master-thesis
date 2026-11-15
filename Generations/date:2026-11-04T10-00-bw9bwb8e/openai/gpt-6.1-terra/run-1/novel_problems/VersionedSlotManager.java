@VersionedSlotManager.ThreadSafe
public final class VersionedSlotManager {
    @interface ThreadSafe {}

    private static final long MAX_VALUE = 0x7fffffffffffffffL;

    public enum Status {
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
        private final long size;
        private final SnapshotNode head;

        private Snapshot(long revision, long size, SnapshotNode head) {
            this.revision = revision;
            this.size = size;
            this.head = head;
        }

        public long getRevision() {
            return revision;
        }

        public long getSize() {
            return size;
        }

        public Slot getSlot(long index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException();
            }

            SnapshotNode node = head;
            for (long i = 0; i < index; i++) {
                node = node.next;
            }
            return node.slot;
        }

        public Slot[] getSlots() {
            if (size > 2147483647L) {
                throw new IllegalStateException("Snapshot is too large for an array");
            }

            Slot[] result = new Slot[(int) size];
            SnapshotNode node = head;
            int index = 0;
            while (node != null) {
                result[index++] = node.slot;
                node = node.next;
            }
            return result;
        }
    }

    public static final class MutationResult {
        private final Status status;
        private final long revision;
        private final long reservationId;

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

        /**
         * Returns the assigned ID for a successful reservation, or zero
         * for cancellations and unsuccessful operations.
         */
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
    private long size;
    private long revision;
    private long lastIssuedId;

    public Snapshot getSnapshot() {
        synchronized (lock) {
            SnapshotNode copy = null;
            Node node = head;
            while (node != null) {
                Slot slot = node.slot;
                Slot copiedSlot = new Slot(
                        slot.reservationId, slot.start, slot.end);
                copy = new SnapshotNode(copiedSlot, copy);
                node = node.next;
            }
            return new Snapshot(revision, size, copy);
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

            if (revision == MAX_VALUE || lastIssuedId == MAX_VALUE) {
                throw new IllegalStateException("Revision or reservation ID exhausted");
            }

            long newId = lastIssuedId + 1;
            long newRevision = revision + 1;
            Node newHead = new Node(new Slot(newId, start, end), head);
            MutationResult result =
                    new MutationResult(Status.SUCCESS, newRevision, newId);

            head = newHead;
            size++;
            lastIssuedId = newId;
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
            Node node = head;
            while (node != null && node.slot.reservationId != reservationId) {
                previous = node;
                node = node.next;
            }

            if (node == null) {
                return new MutationResult(Status.NOT_FOUND, revision, 0);
            }

            if (revision == MAX_VALUE) {
                throw new IllegalStateException("Revision exhausted");
            }

            long newRevision = revision + 1;
            MutationResult result =
                    new MutationResult(Status.SUCCESS, newRevision, 0);

            if (previous == null) {
                head = node.next;
            } else {
                previous.next = node.next;
            }
            size--;
            revision = newRevision;
            return result;
        }
    }
}
