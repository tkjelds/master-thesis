@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler lastChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry reservations;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = "";
    }

    private HierarchicalScheduler(Coordinator coordinator,
                                  HierarchicalScheduler parent,
                                  String name) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator) {
            HierarchicalScheduler child = firstChild;
            while (child != null) {
                if (child.name.equals(name)) {
                    return child;
                }
                child = child.nextSibling;
            }

            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name);
            if (lastChild == null) {
                firstChild = created;
            } else {
                lastChild.nextSibling = created;
            }
            lastChild = created;
            return created;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator) {
            HierarchicalScheduler ancestor = this;
            while (ancestor != null) {
                if (hasOverlap(ancestor.reservations, start, end)) {
                    return -1L;
                }
                ancestor = ancestor.parent;
            }

            if (hasOverlapInDescendants(firstChild, start, end)) {
                return -1L;
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation IDs are exhausted");
            }

            long id = coordinator.nextId;
            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId = id + 1L;
            }

            reservations = new ReservationEntry(id, start, end, reservations);
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
            int count = countNodes(this);
            NodeSnapshot[] nodes = new NodeSnapshot[count];
            String[] path = new String[count];
            int[] index = new int[] { 0 };
            fillSnapshot(this, path, 0, nodes, index);
            return new Snapshot(nodes);
        }
    }

    private static boolean hasOverlap(ReservationEntry entry,
                                      long start,
                                      long end) {
        while (entry != null) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
            entry = entry.next;
        }
        return false;
    }

    private static boolean hasOverlapInDescendants(HierarchicalScheduler node,
                                                    long start,
                                                    long end) {
        while (node != null) {
            if (hasOverlap(node.reservations, start, end)
                    || hasOverlapInDescendants(node.firstChild, start, end)) {
                return true;
            }
            node = node.nextSibling;
        }
        return false;
    }

    private static int countNodes(HierarchicalScheduler node) {
        int count = 1;
        HierarchicalScheduler child = node.firstChild;
        while (child != null) {
            count += countNodes(child);
            child = child.nextSibling;
        }
        return count;
    }

    private static void fillSnapshot(HierarchicalScheduler node,
                                     String[] path,
                                     int depth,
                                     NodeSnapshot[] nodes,
                                     int[] index) {
        String[] nodePath = new String[depth];
        for (int i = 0; i < depth; i++) {
            nodePath[i] = path[i];
        }

        int reservationCount = 0;
        ReservationEntry entry = node.reservations;
        while (entry != null) {
            reservationCount++;
            entry = entry.next;
        }

        Reservation[] detachedReservations = new Reservation[reservationCount];
        entry = node.reservations;
        for (int i = 0; i < reservationCount; i++) {
            detachedReservations[i] =
                    new Reservation(entry.id, entry.start, entry.end);
            entry = entry.next;
        }

        nodes[index[0]++] = new NodeSnapshot(nodePath, detachedReservations);

        HierarchicalScheduler child = node.firstChild;
        while (child != null) {
            path[depth] = child.name;
            fillSnapshot(child, path, depth + 1, nodes, index);
            child = child.nextSibling;
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

        public long id() {
            return id;
        }

        public long start() {
            return start;
        }

        public long end() {
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

        public String[] path() {
            String[] copy = new String[path.length];
            for (int i = 0; i < path.length; i++) {
                copy[i] = path[i];
            }
            return copy;
        }

        public Reservation[] reservations() {
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

        public NodeSnapshot[] nodes() {
            NodeSnapshot[] copy = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                copy[i] = nodes[i];
            }
            return copy;
        }
    }

    private static final class Coordinator {
        private long nextId = 1L;
        private boolean idsExhausted;
    }

    private static final class ReservationEntry {
        private final long id;
        private final long start;
        private final long end;
        private ReservationEntry next;

        private ReservationEntry(long id,
                                 long start,
                                 long end,
                                 ReservationEntry next) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.next = next;
        }
    }
}
