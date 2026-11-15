@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final HierarchicalScheduler parent;
    private final String name;
    private final HierarchicalScheduler root;
    private final Object treeMonitor;
    private ChildEntry[] children;
    private int childCount;
    private ReservationEntry[] reservations;
    private int reservationCount;
    private static final Object RESERVATION_ID_LOCK = new Object();
    private static long nextReservationId = 1L;

    public HierarchicalScheduler() {
        this.parent = null;
        this.name = "";
        this.root = this;
        this.treeMonitor = new Object();
        this.children = new ChildEntry[4];
        this.reservations = new ReservationEntry[4];
    }

    private HierarchicalScheduler(HierarchicalScheduler parent, String name, HierarchicalScheduler root) {
        this.parent = parent;
        this.name = name;
        this.root = root;
        this.treeMonitor = root.treeMonitor;
        this.children = new ChildEntry[4];
        this.reservations = new ReservationEntry[4];
    }

    public HierarchicalScheduler child(String name) {
        validateName(name);
        synchronized (treeMonitor) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i].child;
                }
            }
            HierarchicalScheduler child = new HierarchicalScheduler(this, name, root);
            addChild(child);
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be less than end");
        }
        synchronized (treeMonitor) {
            if (hasConflict(start, end)) {
                return -1L;
            }
            long id = allocateReservationId();
            addReservation(id, start, end);
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0L) {
            return false;
        }
        synchronized (treeMonitor) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].reservationId == reservationId) {
                    removeReservationAt(i);
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (treeMonitor) {
            int nodeCount = countNodesInSubtree(this);
            String[] nodePaths = new String[nodeCount];
            int[] pathIndex = new int[] { 0 };
            collectNodePaths(this, nodePaths, pathIndex);

            int reservationCountInSubtree = countReservationsInSubtree(this);
            Reservation[] reservations = new Reservation[reservationCountInSubtree];
            int[] reservationIndex = new int[] { 0 };
            collectReservations(this, reservations, reservationIndex);

            return new Snapshot(nodePaths, reservations);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("name must be non-empty");
        }
    }

    private static long allocateReservationId() {
        synchronized (RESERVATION_ID_LOCK) {
            long id = nextReservationId;
            nextReservationId += 1L;
            return id;
        }
    }

    private static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private boolean hasConflict(long start, long end) {
        for (HierarchicalScheduler current = parent; current != null; current = current.parent) {
            if (current.hasNodeReservationsConflict(start, end)) {
                return true;
            }
        }
        if (hasNodeReservationsConflict(start, end)) {
            return true;
        }
        return hasDescendantConflict(start, end, this);
    }

    private boolean hasNodeReservationsConflict(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            ReservationEntry current = reservations[i];
            if (overlaps(start, end, current.start, current.end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDescendantConflict(long start, long end, HierarchicalScheduler node) {
        for (int i = 0; i < node.childCount; i++) {
            HierarchicalScheduler child = node.children[i].child;
            if (child.hasNodeReservationsConflict(start, end) || hasDescendantConflict(start, end, child)) {
                return true;
            }
        }
        return false;
    }

    private static int countNodesInSubtree(HierarchicalScheduler node) {
        int total = 1;
        for (int i = 0; i < node.childCount; i++) {
            total += countNodesInSubtree(node.children[i].child);
        }
        return total;
    }

    private static int countReservationsInSubtree(HierarchicalScheduler node) {
        int total = node.reservationCount;
        for (int i = 0; i < node.childCount; i++) {
            total += countReservationsInSubtree(node.children[i].child);
        }
        return total;
    }

    private static void collectNodePaths(HierarchicalScheduler node, String[] destinations, int[] index) {
        destinations[index[0]++] = pathOf(node);
        for (int i = 0; i < node.childCount; i++) {
            collectNodePaths(node.children[i].child, destinations, index);
        }
    }

    private static void collectReservations(HierarchicalScheduler node, Reservation[] destinations, int[] index) {
        for (int i = 0; i < node.reservationCount; i++) {
            ReservationEntry entry = node.reservations[i];
            destinations[index[0]++] = new Reservation(entry.reservationId, entry.start, entry.end, pathOf(node));
        }
        for (int i = 0; i < node.childCount; i++) {
            collectReservations(node.children[i].child, destinations, index);
        }
    }

    private void addChild(HierarchicalScheduler child) {
        if (childCount == children.length) {
            ChildEntry[] newArray = new ChildEntry[children.length * 2];
            for (int i = 0; i < childCount; i++) {
                newArray[i] = children[i];
            }
            children = newArray;
        }
        children[childCount++] = new ChildEntry(child.name, child);
    }

    private void addReservation(long reservationId, long start, long end) {
        if (reservationCount == reservations.length) {
            ReservationEntry[] newArray = new ReservationEntry[reservations.length * 2];
            for (int i = 0; i < reservationCount; i++) {
                newArray[i] = reservations[i];
            }
            reservations = newArray;
        }
        reservations[reservationCount++] = new ReservationEntry(reservationId, start, end);
    }

    private void removeReservationAt(int index) {
        reservationCount -= 1;
        for (int i = index; i < reservationCount; i++) {
            reservations[i] = reservations[i + 1];
        }
        reservations[reservationCount] = null;
    }

    private static String pathOf(HierarchicalScheduler node) {
        if (node == null) {
            return "/";
        }
        int depth = 0;
        HierarchicalScheduler current = node;
        while (current != null && current.parent != null) {
            depth += 1;
            current = current.parent;
        }

        String[] names = new String[depth];
        int insertIndex = depth;
        current = node;
        while (current != null) {
            if (current.name != null && current.name.length() > 0) {
                names[--insertIndex] = current.name;
            }
            current = current.parent;
        }

        StringBuilder builder = new StringBuilder();
        builder.append('/');
        for (int i = 0; i < depth; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(names[i]);
        }
        return builder.toString();
    }

    public static final class Snapshot {
        public final String[] nodePaths;
        public final Reservation[] reservations;

        public Snapshot(String[] nodePaths, Reservation[] reservations) {
            this.nodePaths = nodePaths == null ? new String[0] : nodePaths.clone();
            this.reservations = reservations == null ? new Reservation[0] : reservations.clone();
        }
    }

    public static final class Reservation {
        public final long reservationId;
        public final long start;
        public final long end;
        public final String nodePath;

        public Reservation(long reservationId, long start, long end, String nodePath) {
            this.reservationId = reservationId;
            this.start = start;
            this.end = end;
            this.nodePath = nodePath == null ? "/" : nodePath;
        }

        public String toString() {
            return "Reservation[id=" + reservationId + ", start=" + start + ", end=" + end + ", path=" + nodePath + "]";
        }
    }

    private static final class ChildEntry {
        private final String name;
        private final HierarchicalScheduler child;

        private ChildEntry(String name, HierarchicalScheduler child) {
            this.name = name;
            this.child = child;
        }
    }

    private static final class ReservationEntry {
        private final long reservationId;
        private final long start;
        private final long end;

        private ReservationEntry(long reservationId, long start, long end) {
            this.reservationId = reservationId;
            this.start = start;
            this.end = end;
        }
    }
}
