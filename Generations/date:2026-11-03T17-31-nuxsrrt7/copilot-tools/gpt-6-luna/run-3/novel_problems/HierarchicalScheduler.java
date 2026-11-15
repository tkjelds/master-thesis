@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String[] pathSegments;
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry firstReservation;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = "";
        this.pathSegments = new String[0];
    }

    private HierarchicalScheduler(Coordinator coordinator, HierarchicalScheduler parent, String name) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.pathSegments = new String[parent.pathSegments.length + 1];
        for (int i = 0; i < parent.pathSegments.length; i++) {
            this.pathSegments[i] = parent.pathSegments[i];
        }
        this.pathSegments[this.pathSegments.length - 1] = name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler child = firstChild; child != null; child = child.nextSibling) {
                if (child.name.equals(name)) {
                    return child;
                }
            }

            if (coordinator.nodeCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Scheduler tree is too large");
            }

            HierarchicalScheduler child = new HierarchicalScheduler(coordinator, this, name);
            child.nextSibling = firstChild;
            firstChild = child;
            coordinator.nodeCount++;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must satisfy start < end");
        }

        synchronized (coordinator.monitor) {
            HierarchicalScheduler[] stack = new HierarchicalScheduler[coordinator.nodeCount];
            int stackSize = 0;
            stack[stackSize++] = this;

            while (stackSize > 0) {
                HierarchicalScheduler node = stack[--stackSize];
                for (ReservationEntry entry = node.firstReservation; entry != null; entry = entry.next) {
                    if (start < entry.end && entry.start < end) {
                        return -1L;
                    }
                }
                for (HierarchicalScheduler child = node.firstChild; child != null; child = child.nextSibling) {
                    stack[stackSize++] = child;
                }
            }

            for (HierarchicalScheduler ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                for (ReservationEntry entry = ancestor.firstReservation; entry != null; entry = entry.next) {
                    if (start < entry.end && entry.start < end) {
                        return -1L;
                    }
                }
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

            firstReservation = new ReservationEntry(id, start, end, firstReservation);
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
            HierarchicalScheduler[] stack = new HierarchicalScheduler[coordinator.nodeCount];
            NodeSnapshot[] nodes = new NodeSnapshot[coordinator.nodeCount];
            int stackSize = 0;
            int nodeCount = 0;
            stack[stackSize++] = this;

            while (stackSize > 0) {
                HierarchicalScheduler node = stack[--stackSize];

                int reservationCount = 0;
                for (ReservationEntry entry = node.firstReservation; entry != null; entry = entry.next) {
                    reservationCount++;
                }

                Reservation[] reservations = new Reservation[reservationCount];
                int reservationIndex = 0;
                for (ReservationEntry entry = node.firstReservation; entry != null; entry = entry.next) {
                    reservations[reservationIndex++] = new Reservation(entry.id, entry.start, entry.end);
                }
                nodes[nodeCount++] = new NodeSnapshot(node.pathSegments, reservations);

                for (HierarchicalScheduler child = node.firstChild; child != null; child = child.nextSibling) {
                    stack[stackSize++] = child;
                }
            }

            NodeSnapshot[] exactNodes = new NodeSnapshot[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                exactNodes[i] = nodes[i];
            }
            return new Snapshot(exactNodes);
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
        private final String[] pathSegments;
        private final Reservation[] reservations;

        private NodeSnapshot(String[] pathSegments, Reservation[] reservations) {
            this.pathSegments = new String[pathSegments.length];
            for (int i = 0; i < pathSegments.length; i++) {
                this.pathSegments[i] = pathSegments[i];
            }
            this.reservations = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                this.reservations[i] = reservations[i];
            }
        }

        public String getName() {
            return pathSegments.length == 0 ? "" : pathSegments[pathSegments.length - 1];
        }

        public String getPath() {
            if (pathSegments.length == 0) {
                return "/";
            }
            StringBuilder path = new StringBuilder();
            for (String segment : pathSegments) {
                path.append('/').append(segment);
            }
            return path.toString();
        }

        public String[] getPathSegments() {
            String[] copy = new String[pathSegments.length];
            for (int i = 0; i < pathSegments.length; i++) {
                copy[i] = pathSegments[i];
            }
            return copy;
        }

        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                Reservation reservation = reservations[i];
                copy[i] = new Reservation(reservation.id, reservation.start, reservation.end);
            }
            return copy;
        }
    }

    public static final class Snapshot {
        private final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                this.nodes[i] = nodes[i];
            }
        }

        public NodeSnapshot[] getNodes() {
            NodeSnapshot[] copy = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                NodeSnapshot node = nodes[i];
                copy[i] = new NodeSnapshot(node.pathSegments, node.reservations);
            }
            return copy;
        }
    }

    private static final class Coordinator {
        private final Object monitor = new Object();
        private int nodeCount = 1;
        private long nextId = 1L;
        private boolean idsExhausted;
    }

    private static final class ReservationEntry {
        private final long id;
        private final long start;
        private final long end;
        private ReservationEntry next;

        private ReservationEntry(long id, long start, long end, ReservationEntry next) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.next = next;
        }
    }
}
