@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

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
                    ? "/" + escapeName(name)
                    : path + "/" + escapeName(name);
            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, childPath);
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
            for (HierarchicalScheduler ancestor = parent;
                    ancestor != null;
                    ancestor = ancestor.parent) {
                if (hasOverlap(ancestor.reservations, start, end)) {
                    return -1L;
                }
            }

            HierarchicalScheduler[] stack = new HierarchicalScheduler[8];
            int stackSize = 1;
            stack[0] = this;

            while (stackSize > 0) {
                HierarchicalScheduler node = stack[--stackSize];
                if (hasOverlap(node.reservations, start, end)) {
                    return -1L;
                }

                for (HierarchicalScheduler child = node.firstChild;
                        child != null;
                        child = child.nextSibling) {
                    if (stackSize == stack.length) {
                        stack = growNodes(stack);
                    }
                    stack[stackSize++] = child;
                }
            }

            if (coordinator.nextId <= 0L) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            long id = coordinator.nextId;
            coordinator.nextId = id == Long.MAX_VALUE ? 0L : id + 1L;
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
            String[] paths = new String[8];
            int pathCount = 0;
            DetachedReservation[] detached = new DetachedReservation[8];
            int reservationCount = 0;

            HierarchicalScheduler[] stack = new HierarchicalScheduler[8];
            int stackSize = 1;
            stack[0] = this;

            while (stackSize > 0) {
                HierarchicalScheduler node = stack[--stackSize];

                if (pathCount == paths.length) {
                    paths = growStrings(paths);
                }
                paths[pathCount++] = node.path;

                for (ReservationEntry entry = node.reservations;
                        entry != null;
                        entry = entry.next) {
                    if (reservationCount == detached.length) {
                        detached = growReservations(detached);
                    }
                    detached[reservationCount++] =
                            new DetachedReservation(node.path, entry.id, entry.start, entry.end);
                }

                for (HierarchicalScheduler child = node.firstChild;
                        child != null;
                        child = child.nextSibling) {
                    if (stackSize == stack.length) {
                        stack = growNodes(stack);
                    }
                    stack[stackSize++] = child;
                }
            }

            return new Snapshot(
                    copyStrings(paths, pathCount),
                    copyReservations(detached, reservationCount));
        }
    }

    private static boolean hasOverlap(ReservationEntry entries, long start, long end) {
        for (ReservationEntry entry = entries; entry != null; entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private static String escapeName(String value) {
        int extra = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '~' || c == '/') {
                extra++;
            }
        }

        char[] escaped = new char[value.length() + extra];
        int index = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '~') {
                escaped[index++] = '~';
                escaped[index++] = '0';
            } else if (c == '/') {
                escaped[index++] = '~';
                escaped[index++] = '1';
            } else {
                escaped[index++] = c;
            }
        }
        return new String(escaped);
    }

    private static HierarchicalScheduler[] growNodes(HierarchicalScheduler[] source) {
        HierarchicalScheduler[] result = new HierarchicalScheduler[source.length * 2];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] growStrings(String[] source) {
        String[] result = new String[source.length * 2];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static DetachedReservation[] growReservations(DetachedReservation[] source) {
        DetachedReservation[] result = new DetachedReservation[source.length * 2];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copyStrings(String[] source, int length) {
        String[] result = new String[length];
        for (int i = 0; i < length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static DetachedReservation[] copyReservations(
            DetachedReservation[] source, int length) {
        DetachedReservation[] result = new DetachedReservation[length];
        for (int i = 0; i < length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static final class Coordinator {
        private long nextId = 1L;
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

    public static final class DetachedReservation {
        private final String nodePath;
        private final long id;
        private final long start;
        private final long end;

        private DetachedReservation(String nodePath, long id, long start, long end) {
            this.nodePath = nodePath;
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public String nodePath() {
            return nodePath;
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

    public static final class Snapshot {
        private final String[] nodePaths;
        private final DetachedReservation[] reservations;

        private Snapshot(String[] nodePaths, DetachedReservation[] reservations) {
            this.nodePaths = copyStrings(nodePaths, nodePaths.length);
            this.reservations = copyReservations(reservations, reservations.length);
        }

        public String[] nodePaths() {
            return copyStrings(nodePaths, nodePaths.length);
        }

        public DetachedReservation[] reservations() {
            return copyReservations(reservations, reservations.length);
        }
    }
}
