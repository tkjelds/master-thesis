@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        long nextReservationId = 1L;
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;

    private HierarchicalScheduler[] children = new HierarchicalScheduler[0];
    private Reservation[] reservations = new Reservation[0];

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = "";
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

        synchronized (coordinator) {
            for (int i = 0; i < children.length; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }

            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name);
            if (children.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many children");
            }

            HierarchicalScheduler[] expanded =
                    new HierarchicalScheduler[nextCapacity(children.length)];
            for (int i = 0; i < children.length; i++) {
                expanded[i] = children[i];
            }
            expanded[children.length] = created;
            children = expanded;
            return created;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = node.parent) {
                if (hasOverlap(node.reservations, start, end)) {
                    return -1L;
                }
            }

            HierarchicalScheduler[] stack = new HierarchicalScheduler[4];
            int stackSize = 0;
            stack[stackSize++] = this;

            while (stackSize > 0) {
                HierarchicalScheduler node = stack[--stackSize];
                if (hasOverlap(node.reservations, start, end)) {
                    return -1L;
                }

                for (int i = 0; i < node.children.length; i++) {
                    if (stackSize == stack.length) {
                        stack = grow(stack);
                    }
                    stack[stackSize++] = node.children[i];
                }
            }

            if (coordinator.nextReservationId <= 0L) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            long id = coordinator.nextReservationId;
            if (reservations.length == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many reservations");
            }

            Reservation[] expanded = new Reservation[nextCapacity(reservations.length)];
            for (int i = 0; i < reservations.length; i++) {
                expanded[i] = reservations[i];
            }
            expanded[reservations.length] = new Reservation(id, start, end);
            reservations = expanded;
            coordinator.nextReservationId = id + 1L;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].id == reservationId) {
                    for (int j = i + 1; j < reservations.length; j++) {
                        reservations[j - 1] = reservations[j];
                    }
                    reservations[reservations.length - 1] = null;
                    Reservation[] reduced =
                            new Reservation[reservations.length - 1];
                    for (int j = 0; j < reduced.length; j++) {
                        reduced[j] = reservations[j];
                    }
                    reservations = reduced;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator) {
            HierarchicalScheduler[] stack = new HierarchicalScheduler[4];
            int stackSize = 0;
            stack[stackSize++] = this;

            NodeSnapshot[] result = new NodeSnapshot[4];
            int resultSize = 0;

            while (stackSize > 0) {
                HierarchicalScheduler node = stack[--stackSize];

                if (resultSize == result.length) {
                    result = grow(result);
                }
                result[resultSize++] = new NodeSnapshot(
                        relativePath(node),
                        copyReservations(node.reservations));

                for (int i = node.children.length - 1; i >= 0; i--) {
                    if (stackSize == stack.length) {
                        stack = grow(stack);
                    }
                    stack[stackSize++] = node.children[i];
                }
            }

            NodeSnapshot[] exact = new NodeSnapshot[resultSize];
            for (int i = 0; i < resultSize; i++) {
                exact[i] = result[i];
            }
            return new Snapshot(exact);
        }
    }

    private String[] relativePath(HierarchicalScheduler node) {
        int length = 0;
        for (HierarchicalScheduler current = node;
                current != this;
                current = current.parent) {
            length++;
        }

        String[] path = new String[length];
        HierarchicalScheduler current = node;
        for (int i = length - 1; i >= 0; i--) {
            path[i] = current.name;
            current = current.parent;
        }
        return path;
    }

    private static boolean hasOverlap(
            Reservation[] existing,
            long start,
            long end) {
        for (int i = 0; i < existing.length; i++) {
            if (start < existing[i].end && existing[i].start < end) {
                return true;
            }
        }
        return false;
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] copy = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            Reservation reservation = source[i];
            copy[i] = new Reservation(
                    reservation.id,
                    reservation.start,
                    reservation.end);
        }
        return copy;
    }

    private static int nextCapacity(int current) {
        if (current == 0) {
            return 4;
        }
        if (current > Integer.MAX_VALUE / 2) {
            return Integer.MAX_VALUE;
        }
        return current * 2;
    }

    private static HierarchicalScheduler[] grow(
            HierarchicalScheduler[] source) {
        int capacity = nextCapacity(source.length);
        if (capacity <= source.length) {
            throw new IllegalStateException("Tree is too large");
        }
        HierarchicalScheduler[] expanded =
                new HierarchicalScheduler[capacity];
        for (int i = 0; i < source.length; i++) {
            expanded[i] = source[i];
        }
        return expanded;
    }

    private static NodeSnapshot[] grow(NodeSnapshot[] source) {
        int capacity = nextCapacity(source.length);
        if (capacity <= source.length) {
            throw new IllegalStateException("Snapshot is too large");
        }
        NodeSnapshot[] expanded = new NodeSnapshot[capacity];
        for (int i = 0; i < source.length; i++) {
            expanded[i] = source[i];
        }
        return expanded;
    }

    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;

        public Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class NodeSnapshot {
        public final String[] path;
        public final Reservation[] reservations;

        public NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = copyStrings(path);
            this.reservations = copyReservations(reservations);
        }

        public String[] getPath() {
            return copyStrings(path);
        }

        public Reservation[] getReservations() {
            return copyReservations(reservations);
        }
    }

    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        public Snapshot(NodeSnapshot[] nodes) {
            this.nodes = copyNodes(nodes);
        }

        public NodeSnapshot[] getNodes() {
            return copyNodes(nodes);
        }
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static NodeSnapshot[] copyNodes(NodeSnapshot[] source) {
        NodeSnapshot[] copy = new NodeSnapshot[source.length];
        for (int i = 0; i < source.length; i++) {
            NodeSnapshot node = source[i];
            copy[i] = new NodeSnapshot(node.path, node.reservations);
        }
        return copy;
    }
}
