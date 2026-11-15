@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        final Object monitor = new Object();
        long nextReservationId = 1;
    }

    private static final class ReservationEntry {
        final long id;
        final long start;
        final long end;
        ReservationEntry next;

        ReservationEntry(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class NodeSnapshot {
        public final String path;
        private final Reservation[] reservations;

        private NodeSnapshot(String path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations.clone();
        }

        public Reservation[] getReservations() {
            return reservations.clone();
        }
    }

    public static final class Snapshot {
        private final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes.clone();
        }

        public NodeSnapshot[] getNodes() {
            return nodes.clone();
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    // These fields are accessed only while holding coordinator.monitor.
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry firstReservation;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = "";
        this.path = "/";
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
            HierarchicalScheduler parent,
            String name,
            String path) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.path = path;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler child = firstChild;
                    child != null;
                    child = child.nextSibling) {
                if (child.name.equals(name)) {
                    return child;
                }
            }

            String childPath = path.equals("/")
                    ? path + escapePathComponent(name)
                    : path + "/" + escapePathComponent(name);
            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, childPath);
            child.nextSibling = firstChild;
            firstChild = child;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation start must be less than end");
        }

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = node.parent) {
                if (hasOverlap(node.firstReservation, start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler node = firstChild;
                    node != null;
                    node = nextInSubtree(node, this)) {
                if (hasOverlap(node.firstReservation, start, end)) {
                    return -1;
                }
            }

            long id = coordinator.nextReservationId;
            if (id <= 0) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }
            coordinator.nextReservationId =
                    id == Long.MAX_VALUE ? 0 : id + 1;

            ReservationEntry entry = new ReservationEntry(id, start, end);
            entry.next = firstReservation;
            firstReservation = entry;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator.monitor) {
            ReservationEntry previous = null;
            ReservationEntry current = firstReservation;
            while (current != null) {
                if (current.id == reservationId) {
                    if (previous == null) {
                        firstReservation = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    return true;
                }
                previous = current;
                current = current.next;
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator.monitor) {
            int nodeCount = 0;
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                nodeCount++;
            }

            NodeSnapshot[] result = new NodeSnapshot[nodeCount];
            int index = 0;
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                int reservationCount = 0;
                for (ReservationEntry entry = node.firstReservation;
                        entry != null;
                        entry = entry.next) {
                    reservationCount++;
                }

                Reservation[] reservations = new Reservation[reservationCount];
                int reservationIndex = 0;
                for (ReservationEntry entry = node.firstReservation;
                        entry != null;
                        entry = entry.next) {
                    reservations[reservationIndex++] =
                            new Reservation(entry.id, entry.start, entry.end);
                }
                result[index++] = new NodeSnapshot(node.path, reservations);
            }
            return new Snapshot(result);
        }
    }

    private static boolean hasOverlap(
            ReservationEntry entries,
            long start,
            long end) {
        for (ReservationEntry entry = entries; entry != null; entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler node,
            HierarchicalScheduler subtreeRoot) {
        if (node.firstChild != null) {
            return node.firstChild;
        }

        while (node != subtreeRoot && node.nextSibling == null) {
            node = node.parent;
        }
        return node == subtreeRoot ? null : node.nextSibling;
    }

    private static String escapePathComponent(String component) {
        char[] escaped = new char[component.length() * 2];
        int length = 0;
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            if (c == '/' || c == '\\') {
                escaped[length++] = '\\';
            }
            escaped[length++] = c;
        }
        return new String(escaped, 0, length);
    }
}
