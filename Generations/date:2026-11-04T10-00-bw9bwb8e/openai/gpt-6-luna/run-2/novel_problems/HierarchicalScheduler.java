@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        long nextId = 1;
        boolean idsExhausted;
    }

    private static final class ReservationEntry {
        final Reservation reservation;
        ReservationEntry next;

        ReservationEntry(Reservation reservation, ReservationEntry next) {
            this.reservation = reservation;
            this.next = next;
        }
    }

    private static final class NodeStack {
        private HierarchicalScheduler[] nodes = new HierarchicalScheduler[8];
        private int size;

        void push(HierarchicalScheduler node) {
            if (size == nodes.length) {
                if (nodes.length == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Tree is too large to traverse");
                }
                int newLength = nodes.length <= Integer.MAX_VALUE / 2
                        ? nodes.length * 2 : Integer.MAX_VALUE;
                HierarchicalScheduler[] expanded =
                        new HierarchicalScheduler[newLength];
                System.arraycopy(nodes, 0, expanded, 0, size);
                nodes = expanded;
            }
            nodes[size++] = node;
        }

        HierarchicalScheduler pop() {
            return nodes[--size];
        }

        boolean isEmpty() {
            return size == 0;
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
        public final Reservation[] reservations;

        private NodeSnapshot(String path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations.clone();
        }

        public Reservation[] reservations() {
            return reservations.clone();
        }
    }

    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes.clone();
        }

        public NodeSnapshot[] nodes() {
            return nodes.clone();
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    // These links and reservation entries are accessed only while holding
    // coordinator's monitor.
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry reservations;

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

        synchronized (coordinator) {
            for (HierarchicalScheduler child = firstChild;
                 child != null;
                 child = child.nextSibling) {
                if (child.name.equals(name)) {
                    return child;
                }
            }

            String childPath = path.equals("/")
                    ? path + name
                    : path + "/" + name;
            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, childPath);
            child.nextSibling = firstChild;
            firstChild = child;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must satisfy start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = parent;
                 node != null;
                 node = node.parent) {
                if (hasOverlap(node.reservations, start, end)) {
                    return -1;
                }
            }

            NodeStack stack = new NodeStack();
            for (HierarchicalScheduler child = firstChild;
                 child != null;
                 child = child.nextSibling) {
                stack.push(child);
            }

            while (!stack.isEmpty()) {
                HierarchicalScheduler node = stack.pop();
                if (hasOverlap(node.reservations, start, end)) {
                    return -1;
                }
                for (HierarchicalScheduler child = node.firstChild;
                     child != null;
                     child = child.nextSibling) {
                    stack.push(child);
                }
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            long id = coordinator.nextId;
            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId = id + 1;
            }

            Reservation reservation = new Reservation(id, start, end);
            reservations = new ReservationEntry(reservation, reservations);
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            ReservationEntry previous = null;
            ReservationEntry current = reservations;

            while (current != null) {
                if (current.reservation.id == reservationId) {
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
            NodeStack stack = new NodeStack();
            stack.push(this);

            int nodeCount = 0;
            while (!stack.isEmpty()) {
                HierarchicalScheduler node = stack.pop();
                if (nodeCount == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Tree is too large to snapshot");
                }
                nodeCount++;

                for (HierarchicalScheduler child = node.firstChild;
                     child != null;
                     child = child.nextSibling) {
                    stack.push(child);
                }
            }

            NodeSnapshot[] result = new NodeSnapshot[nodeCount];
            stack.push(this);
            int index = 0;

            while (!stack.isEmpty()) {
                HierarchicalScheduler node = stack.pop();

                int reservationCount = 0;
                for (ReservationEntry entry = node.reservations;
                     entry != null;
                     entry = entry.next) {
                    if (reservationCount == Integer.MAX_VALUE) {
                        throw new IllegalStateException(
                                "Too many reservations to snapshot");
                    }
                    reservationCount++;
                }

                Reservation[] copiedReservations =
                        new Reservation[reservationCount];
                int reservationIndex = 0;
                for (ReservationEntry entry = node.reservations;
                     entry != null;
                     entry = entry.next) {
                    copiedReservations[reservationIndex++] = entry.reservation;
                }

                result[index++] =
                        new NodeSnapshot(node.path, copiedReservations);

                for (HierarchicalScheduler child = node.firstChild;
                     child != null;
                     child = child.nextSibling) {
                    stack.push(child);
                }
            }

            return new Snapshot(result);
        }
    }

    private static boolean hasOverlap(
            ReservationEntry entries,
            long start,
            long end) {
        for (ReservationEntry entry = entries;
             entry != null;
             entry = entry.next) {
            Reservation reservation = entry.reservation;
            if (start < reservation.end && reservation.start < end) {
                return true;
            }
        }
        return false;
    }
}
