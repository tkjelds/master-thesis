@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 4;
    private static final int MAX_ARRAY_CAPACITY = Integer.MAX_VALUE - 8;

    private static final class Coordinator {
        private final Object monitor = new Object();
        private long nextReservationId = 1;
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private HierarchicalScheduler[] children = new HierarchicalScheduler[INITIAL_CAPACITY];
    private int childCount;
    private Reservation[] reservations = new Reservation[INITIAL_CAPACITY];
    private int reservationCount;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
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
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }

            if (childCount == children.length) {
                HierarchicalScheduler[] expanded =
                        new HierarchicalScheduler[grownCapacity(children.length)];
                for (int i = 0; i < childCount; i++) {
                    expanded[i] = children[i];
                }
                children = expanded;
            }

            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name);
            children[childCount++] = created;
            return created;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must have start < end");
        }

        synchronized (coordinator.monitor) {
            if (hasConflict(start, end)) {
                return -1;
            }

            long id = coordinator.nextReservationId;
            if (id <= 0) {
                throw new IllegalStateException("Reservation IDs are exhausted");
            }

            if (reservationCount == reservations.length) {
                Reservation[] expanded =
                        new Reservation[grownCapacity(reservations.length)];
                for (int i = 0; i < reservationCount; i++) {
                    expanded[i] = reservations[i];
                }
                reservations = expanded;
            }

            reservations[reservationCount++] = new Reservation(id, start, end);
            coordinator.nextReservationId =
                    id == Long.MAX_VALUE ? 0 : id + 1;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator.monitor) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    int remaining = reservationCount - i - 1;
                    for (int j = 0; j < remaining; j++) {
                        reservations[i + j] = reservations[i + j + 1];
                    }
                    reservations[--reservationCount] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator.monitor) {
            NodeSnapshot[] nodes = new NodeSnapshot[INITIAL_CAPACITY];
            int nodeCount = 0;
            HierarchicalScheduler[] stack =
                    new HierarchicalScheduler[INITIAL_CAPACITY];
            int stackSize = 1;
            stack[0] = this;

            while (stackSize > 0) {
                HierarchicalScheduler current = stack[--stackSize];

                if (nodeCount == nodes.length) {
                    NodeSnapshot[] expanded =
                            new NodeSnapshot[grownCapacity(nodes.length)];
                    for (int i = 0; i < nodeCount; i++) {
                        expanded[i] = nodes[i];
                    }
                    nodes = expanded;
                }
                nodes[nodeCount++] = current.createNodeSnapshot();

                for (int i = current.childCount - 1; i >= 0; i--) {
                    if (stackSize == stack.length) {
                        HierarchicalScheduler[] expanded =
                                new HierarchicalScheduler[grownCapacity(stack.length)];
                        for (int j = 0; j < stackSize; j++) {
                            expanded[j] = stack[j];
                        }
                        stack = expanded;
                    }
                    stack[stackSize++] = current.children[i];
                }
            }

            NodeSnapshot[] result = new NodeSnapshot[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                result[i] = nodes[i];
            }
            return new Snapshot(result);
        }
    }

    private boolean hasConflict(long start, long end) {
        for (HierarchicalScheduler ancestor = parent;
                ancestor != null;
                ancestor = ancestor.parent) {
            if (ancestor.hasLocalConflict(start, end)) {
                return true;
            }
        }

        HierarchicalScheduler[] stack =
                new HierarchicalScheduler[INITIAL_CAPACITY];
        int stackSize = 1;
        stack[0] = this;

        while (stackSize > 0) {
            HierarchicalScheduler current = stack[--stackSize];
            if (current.hasLocalConflict(start, end)) {
                return true;
            }

            for (int i = 0; i < current.childCount; i++) {
                if (stackSize == stack.length) {
                    HierarchicalScheduler[] expanded =
                            new HierarchicalScheduler[grownCapacity(stack.length)];
                    for (int j = 0; j < stackSize; j++) {
                        expanded[j] = stack[j];
                    }
                    stack = expanded;
                }
                stack[stackSize++] = current.children[i];
            }
        }

        return false;
    }

    private boolean hasLocalConflict(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            Reservation existing = reservations[i];
            if (start < existing.end && existing.start < end) {
                return true;
            }
        }
        return false;
    }

    private NodeSnapshot createNodeSnapshot() {
        int depth = 0;
        for (HierarchicalScheduler current = this;
                current.parent != null;
                current = current.parent) {
            depth++;
        }

        String[] path = new String[depth];
        HierarchicalScheduler current = this;
        for (int i = depth - 1; i >= 0; i--) {
            path[i] = current.name;
            current = current.parent;
        }

        Reservation[] reservationCopies = new Reservation[reservationCount];
        for (int i = 0; i < reservationCount; i++) {
            Reservation reservation = reservations[i];
            reservationCopies[i] =
                    new Reservation(reservation.id, reservation.start, reservation.end);
        }

        return new NodeSnapshot(path, reservationCopies);
    }

    private static int grownCapacity(int current) {
        if (current >= MAX_ARRAY_CAPACITY) {
            throw new IllegalStateException("Scheduler capacity is exhausted");
        }

        long doubled = (long) current * 2;
        return doubled > MAX_ARRAY_CAPACITY
                ? MAX_ARRAY_CAPACITY
                : (int) doubled;
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
}
