@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private Reservation firstReservation;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
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

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler child = firstChild;
                 child != null;
                 child = child.nextSibling) {
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
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler ancestor = parent;
                 ancestor != null;
                 ancestor = ancestor.parent) {
                if (hasOverlap(ancestor.firstReservation, start, end)) {
                    return -1L;
                }
            }

            if (subtreeHasOverlap(this, start, end)) {
                return -1L;
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            long id = coordinator.nextId;
            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId++;
            }

            Reservation reservation = new Reservation(id, start, end);
            reservation.next = firstReservation;
            firstReservation = reservation;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator.monitor) {
            Reservation previous = null;
            Reservation current = firstReservation;

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
            int nodeCount = countNodes(this);
            NodeSnapshot[] nodes = new NodeSnapshot[nodeCount];

            int depth = 0;
            for (HierarchicalScheduler node = this;
                 node.parent != null;
                 node = node.parent) {
                depth++;
            }

            String[] basePath = new String[depth];
            HierarchicalScheduler node = this;
            for (int i = depth - 1; i >= 0; i--) {
                basePath[i] = node.name;
                node = node.parent;
            }

            fillSnapshots(this, basePath, nodes, 0);
            return new Snapshot(nodes);
        }
    }

    private static boolean hasOverlap(Reservation reservation,
                                      long start,
                                      long end) {
        for (Reservation current = reservation;
             current != null;
             current = current.next) {
            if (start < current.end && current.start < end) {
                return true;
            }
        }
        return false;
    }

    private static boolean subtreeHasOverlap(HierarchicalScheduler node,
                                             long start,
                                             long end) {
        if (hasOverlap(node.firstReservation, start, end)) {
            return true;
        }

        for (HierarchicalScheduler child = node.firstChild;
             child != null;
             child = child.nextSibling) {
            if (subtreeHasOverlap(child, start, end)) {
                return true;
            }
        }

        return false;
    }

    private static int countNodes(HierarchicalScheduler node) {
        int count = 1;
        for (HierarchicalScheduler child = node.firstChild;
             child != null;
             child = child.nextSibling) {
            count += countNodes(child);
        }
        return count;
    }

    private static int countReservations(Reservation first) {
        int count = 0;
        for (Reservation current = first; current != null; current = current.next) {
            count++;
        }
        return count;
    }

    private static int fillSnapshots(HierarchicalScheduler node,
                                     String[] path,
                                     NodeSnapshot[] snapshots,
                                     int index) {
        int reservationCount = countReservations(node.firstReservation);
        ReservationSnapshot[] reservations =
                new ReservationSnapshot[reservationCount];

        int reservationIndex = 0;
        for (Reservation current = node.firstReservation;
             current != null;
             current = current.next) {
            reservations[reservationIndex++] =
                    new ReservationSnapshot(current.id, current.start, current.end);
        }

        snapshots[index++] = new NodeSnapshot(path, reservations);

        for (HierarchicalScheduler child = node.firstChild;
             child != null;
             child = child.nextSibling) {
            String[] childPath = new String[path.length + 1];
            for (int i = 0; i < path.length; i++) {
                childPath[i] = path[i];
            }
            childPath[path.length] = child.name;
            index = fillSnapshots(child, childPath, snapshots, index);
        }

        return index;
    }

    private static final class Coordinator {
        private final Object monitor = new Object();
        private long nextId = 1L;
        private boolean idsExhausted;
    }

    private static final class Reservation {
        private final long id;
        private final long start;
        private final long end;
        private Reservation next;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class ReservationSnapshot {
        public final long id;
        public final long start;
        public final long end;

        public ReservationSnapshot(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class NodeSnapshot {
        public final String[] pathSegments;
        public final ReservationSnapshot[] reservations;

        public NodeSnapshot(String[] pathSegments,
                            ReservationSnapshot[] reservations) {
            if (pathSegments == null || reservations == null) {
                throw new IllegalArgumentException("Snapshot arrays must not be null");
            }

            this.pathSegments = new String[pathSegments.length];
            for (int i = 0; i < pathSegments.length; i++) {
                this.pathSegments[i] = pathSegments[i];
            }

            this.reservations = new ReservationSnapshot[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                this.reservations[i] = reservations[i];
            }
        }
    }

    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        public Snapshot(NodeSnapshot[] nodes) {
            if (nodes == null) {
                throw new IllegalArgumentException("Snapshot nodes must not be null");
            }

            this.nodes = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                this.nodes[i] = nodes[i];
            }
        }
    }
}
