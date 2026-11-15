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
            for (HierarchicalScheduler child : children) {
                if (child.name.equals(name)) {
                    return child;
                }
            }

            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name);
            HierarchicalScheduler[] expanded =
                    new HierarchicalScheduler[children.length + 1];
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

            if (hasOverlapInSubtree(this, start, end)) {
                return -1L;
            }

            if (coordinator.nextReservationId <= 0L) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            long id = coordinator.nextReservationId;
            Reservation reservation = new Reservation(id, start, end);

            Reservation[] expanded =
                    new Reservation[reservations.length + 1];
            for (int i = 0; i < reservations.length; i++) {
                expanded[i] = reservations[i];
            }
            expanded[reservations.length] = reservation;
            reservations = expanded;

            coordinator.nextReservationId = id + 1L;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].id == reservationId) {
                    Reservation[] reduced =
                            new Reservation[reservations.length - 1];
                    for (int j = 0; j < i; j++) {
                        reduced[j] = reservations[j];
                    }
                    for (int j = i + 1; j < reservations.length; j++) {
                        reduced[j - 1] = reservations[j];
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
            int count = countNodes(this);
            NodeSnapshot[] nodes = new NodeSnapshot[count];
            String[] pathComponents = new String[countTreeDepth(this)];
            int next = fillSnapshots(this, pathComponents, 0, nodes, 0);
            return new Snapshot(nodes, next);
        }
    }

    private static boolean hasOverlap(
            Reservation[] reservations, long start, long end) {
        for (Reservation reservation : reservations) {
            if (start < reservation.end && reservation.start < end) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOverlapInSubtree(
            HierarchicalScheduler node, long start, long end) {
        if (hasOverlap(node.reservations, start, end)) {
            return true;
        }
        for (HierarchicalScheduler child : node.children) {
            if (hasOverlapInSubtree(child, start, end)) {
                return true;
            }
        }
        return false;
    }

    private static int countNodes(HierarchicalScheduler node) {
        int count = 1;
        for (HierarchicalScheduler child : node.children) {
            count += countNodes(child);
        }
        return count;
    }

    private static int countTreeDepth(HierarchicalScheduler node) {
        int maximum = 0;
        for (HierarchicalScheduler child : node.children) {
            int depth = 1 + countTreeDepth(child);
            if (depth > maximum) {
                maximum = depth;
            }
        }
        return maximum;
    }

    private static int fillSnapshots(
            HierarchicalScheduler node,
            String[] pathComponents,
            int depth,
            NodeSnapshot[] output,
            int index) {
        if (depth > 0) {
            pathComponents[depth - 1] = node.name;
        }

        String[] components = new String[depth];
        for (int i = 0; i < depth; i++) {
            components[i] = pathComponents[i];
        }

        output[index++] = new NodeSnapshot(
                makePath(components),
                components,
                node.reservations);

        for (HierarchicalScheduler child : node.children) {
            index = fillSnapshots(
                    child, pathComponents, depth + 1, output, index);
        }
        return index;
    }

    private static String makePath(String[] components) {
        if (components.length == 0) {
            return "/";
        }

        StringBuilder result = new StringBuilder();
        for (String component : components) {
            result.append('/');
            for (int i = 0; i < component.length(); i++) {
                char c = component.charAt(i);
                if (c == '/' || c == '\\') {
                    result.append('\\');
                }
                result.append(c);
            }
        }
        return result.toString();
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
        public final String path;
        public final String[] pathComponents;
        public final Reservation[] reservations;

        private NodeSnapshot(
                String path,
                String[] pathComponents,
                Reservation[] reservations) {
            this.path = path;
            this.pathComponents = copyStrings(pathComponents);
            this.reservations = copyReservations(reservations);
        }

        public String getPath() {
            return path;
        }

        public String[] getPathComponents() {
            return copyStrings(pathComponents);
        }

        public Reservation[] getReservations() {
            return copyReservations(reservations);
        }
    }

    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes, int count) {
            this.nodes = new NodeSnapshot[count];
            for (int i = 0; i < count; i++) {
                this.nodes[i] = nodes[i];
            }
        }

        public NodeSnapshot[] getNodes() {
            NodeSnapshot[] copy = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                copy[i] = nodes[i];
            }
            return copy;
        }
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] copy = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
