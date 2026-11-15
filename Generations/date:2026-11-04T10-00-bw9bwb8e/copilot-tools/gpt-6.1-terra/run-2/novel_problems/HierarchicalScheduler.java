@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        long nextId = 1;
    }

    private static final class ReservationEntry {
        final long id;
        final long start;
        final long end;
        ReservationEntry next;

        ReservationEntry(long id, long start, long end, ReservationEntry next) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.next = next;
        }
    }

    public static final class Reservation {
        private final long id;
        private final long start;
        private final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long getId() {
            return id;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    public static final class NodeSnapshot {
        private final String[] path;
        private final Reservation[] reservations;

        private NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }

        // Paths contain names from the tree root to this node; the root path is empty.
        public String[] getPath() {
            String[] copy = new String[path.length];
            for (int i = 0; i < path.length; i++) {
                copy[i] = path[i];
            }
            return copy;
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
        }
    }

    public static final class Snapshot {
        private final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }

        public NodeSnapshot[] getNodes() {
            NodeSnapshot[] copy = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                copy[i] = nodes[i];
            }
            return copy;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;

    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry reservations;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
    }

    private HierarchicalScheduler(
            Coordinator coordinator, HierarchicalScheduler parent, String name) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler child = firstChild;
                    child != null; child = child.nextSibling) {
                if (child.name.equals(name)) {
                    return child;
                }
            }

            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name);
            child.nextSibling = firstChild;
            firstChild = child;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must have start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = parent;
                    node != null; node = node.parent) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler node = this;
                    node != null; node = nextInSubtree(node, this)) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
            }

            // Zero marks exhaustion after the last positive long ID is assigned.
            if (coordinator.nextId == 0) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            long id = coordinator.nextId;
            ReservationEntry entry = new ReservationEntry(id, start, end, reservations);
            coordinator.nextId = id == Long.MAX_VALUE ? 0 : id + 1;
            reservations = entry;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            ReservationEntry previous = null;
            ReservationEntry current = reservations;

            while (current != null) {
                if (current.id == reservationId) {
                    if (previous == null) {
                        reservations = current.next;
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
        synchronized (coordinator) {
            int nodeCount = 0;
            for (HierarchicalScheduler node = this;
                    node != null; node = nextInSubtree(node, this)) {
                if (nodeCount == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Too many nodes for a snapshot");
                }
                nodeCount++;
            }

            NodeSnapshot[] nodes = new NodeSnapshot[nodeCount];
            int index = 0;
            for (HierarchicalScheduler node = this;
                    node != null; node = nextInSubtree(node, this)) {
                nodes[index++] = node.copySnapshot();
            }
            return new Snapshot(nodes);
        }
    }

    private boolean hasOverlap(long start, long end) {
        for (ReservationEntry entry = reservations;
                entry != null; entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private NodeSnapshot copySnapshot() {
        int depth = 0;
        for (HierarchicalScheduler node = this;
                node.parent != null; node = node.parent) {
            if (depth == Integer.MAX_VALUE) {
                throw new IllegalStateException("Path too deep for a snapshot");
            }
            depth++;
        }

        String[] path = new String[depth];
        HierarchicalScheduler node = this;
        for (int i = depth - 1; i >= 0; i--) {
            path[i] = node.name;
            node = node.parent;
        }

        int count = 0;
        for (ReservationEntry entry = reservations;
                entry != null; entry = entry.next) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many reservations for a snapshot");
            }
            count++;
        }

        Reservation[] copies = new Reservation[count];
        int index = 0;
        for (ReservationEntry entry = reservations;
                entry != null; entry = entry.next) {
            copies[index++] = new Reservation(entry.id, entry.start, entry.end);
        }
        return new NodeSnapshot(path, copies);
    }

    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler node, HierarchicalScheduler subtreeRoot) {
        if (node.firstChild != null) {
            return node.firstChild;
        }
        while (node != subtreeRoot) {
            if (node.nextSibling != null) {
                return node.nextSibling;
            }
            node = node.parent;
        }
        return null;
    }
}
