@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        long nextId = 1;
        boolean idsExhausted;
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
        public final String[] pathSegments;
        public final Reservation[] reservations;

        private NodeSnapshot(String[] pathSegments, Reservation[] reservations) {
            this.pathSegments = copyStrings(pathSegments);
            this.reservations = copyReservations(reservations);
        }
    }

    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = copyNodes(nodes);
        }
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
            for (int i = 0; i < children.length; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }

            HierarchicalScheduler child =
                new HierarchicalScheduler(coordinator, this, name);
            children = appendChild(children, child);
            return child;
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
                    return -1;
                }
            }

            if (subtreeHasOverlap(this, start, end)) {
                return -1;
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation IDs are exhausted");
            }

            long id = coordinator.nextId;
            Reservation reservation = new Reservation(id, start, end);
            reservations = appendReservation(reservations, reservation);

            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId = id + 1;
            }
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].id == reservationId) {
                    Reservation[] updated =
                        new Reservation[reservations.length - 1];
                    for (int j = 0; j < i; j++) {
                        updated[j] = reservations[j];
                    }
                    for (int j = i + 1; j < reservations.length; j++) {
                        updated[j - 1] = reservations[j];
                    }
                    reservations = updated;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator) {
            NodeSnapshot[] result =
                new NodeSnapshot[countNodes(this)];
            int[] position = new int[] { 0 };
            fillSnapshot(this, new String[0], result, position);
            return new Snapshot(result);
        }
    }

    private static boolean hasOverlap(Reservation[] reservations,
                                      long start,
                                      long end) {
        for (int i = 0; i < reservations.length; i++) {
            if (start < reservations[i].end && reservations[i].start < end) {
                return true;
            }
        }
        return false;
    }

    private static boolean subtreeHasOverlap(HierarchicalScheduler node,
                                             long start,
                                             long end) {
        if (hasOverlap(node.reservations, start, end)) {
            return true;
        }
        for (int i = 0; i < node.children.length; i++) {
            if (subtreeHasOverlap(node.children[i], start, end)) {
                return true;
            }
        }
        return false;
    }

    private static int countNodes(HierarchicalScheduler node) {
        int count = 1;
        for (int i = 0; i < node.children.length; i++) {
            count += countNodes(node.children[i]);
        }
        return count;
    }

    private static void fillSnapshot(HierarchicalScheduler node,
                                     String[] path,
                                     NodeSnapshot[] output,
                                     int[] position) {
        output[position[0]++] =
            new NodeSnapshot(path, node.reservations);

        for (int i = 0; i < node.children.length; i++) {
            HierarchicalScheduler child = node.children[i];
            String[] childPath = new String[path.length + 1];
            for (int j = 0; j < path.length; j++) {
                childPath[j] = path[j];
            }
            childPath[path.length] = child.name;
            fillSnapshot(child, childPath, output, position);
        }
    }

    private static HierarchicalScheduler[] appendChild(
        HierarchicalScheduler[] source,
        HierarchicalScheduler child) {
        int capacity = expandedCapacity(source.length);
        HierarchicalScheduler[] result =
            new HierarchicalScheduler[capacity];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        result[source.length] = child;
        return result;
    }

    private static Reservation[] appendReservation(Reservation[] source,
                                                   Reservation reservation) {
        int capacity = expandedCapacity(source.length);
        Reservation[] result = new Reservation[capacity];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        result[source.length] = reservation;
        return result;
    }

    private static int expandedCapacity(int currentLength) {
        if (currentLength == Integer.MAX_VALUE) {
            throw new IllegalStateException("Structure capacity is exhausted");
        }
        if (currentLength == 0) {
            return 4;
        }
        if (currentLength > Integer.MAX_VALUE / 2) {
            return Integer.MAX_VALUE;
        }
        return currentLength * 2;
    }

    private static String[] copyStrings(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static Reservation[] copyReservations(Reservation[] source) {
        Reservation[] result = new Reservation[source.length];
        for (int i = 0; i < source.length; i++) {
            Reservation reservation = source[i];
            result[i] = new Reservation(
                reservation.id, reservation.start, reservation.end);
        }
        return result;
    }

    private static NodeSnapshot[] copyNodes(NodeSnapshot[] source) {
        NodeSnapshot[] result = new NodeSnapshot[source.length];
        for (int i = 0; i < source.length; i++) {
            NodeSnapshot node = source[i];
            result[i] = new NodeSnapshot(
                node.pathSegments, node.reservations);
        }
        return result;
    }
}
