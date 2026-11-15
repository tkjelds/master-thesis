@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        final Object monitor = new Object();
        long nextId = 1L;
    }

    private static final class ReservationEntry {
        final long id;
        final long start;
        final long end;
        ReservationEntry next;

        ReservationEntry(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler lastChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry firstReservation;
    private ReservationEntry lastReservation;

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

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler child = firstChild;
                 child != null;
                 child = child.nextSibling) {
                if (child.name.equals(name)) {
                    return child;
                }
            }

            String childPath = path
                    + (path.equals("/") ? "" : "/")
                    + escapeSegment(name);
            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, childPath);

            if (lastChild == null) {
                firstChild = child;
            } else {
                lastChild.nextSibling = child;
            }
            lastChild = child;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator.monitor) {
            if (conflicts(start, end)) {
                return -1L;
            }

            long id = coordinator.nextId;
            if (id <= 0L) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            ReservationEntry entry = new ReservationEntry(id, start, end);
            if (lastReservation == null) {
                firstReservation = entry;
            } else {
                lastReservation.next = entry;
            }
            lastReservation = entry;
            coordinator.nextId = id == Long.MAX_VALUE ? Long.MIN_VALUE : id + 1L;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        if (reservationId <= 0L) {
            return false;
        }

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
                    if (lastReservation == current) {
                        lastReservation = previous;
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
            HierarchicalScheduler[] nodes = new HierarchicalScheduler[8];
            int nodeCount = 1;
            nodes[0] = this;

            String[] paths = new String[8];
            int pathCount = 0;
            DetachedReservation[] reservations = new DetachedReservation[8];
            int reservationCount = 0;

            for (int index = 0; index < nodeCount; index++) {
                HierarchicalScheduler node = nodes[index];

                if (pathCount == paths.length) {
                    paths = grow(paths);
                }
                paths[pathCount++] = node.path;

                for (ReservationEntry entry = node.firstReservation;
                     entry != null;
                     entry = entry.next) {
                    if (reservationCount == reservations.length) {
                        reservations = grow(reservations);
                    }
                    reservations[reservationCount++] =
                            new DetachedReservation(node.path, entry.id, entry.start, entry.end);
                }

                for (HierarchicalScheduler child = node.firstChild;
                     child != null;
                     child = child.nextSibling) {
                    if (nodeCount == nodes.length) {
                        nodes = grow(nodes);
                    }
                    nodes[nodeCount++] = child;
                }
            }

            return new Snapshot(
                    copy(paths, pathCount),
                    copy(reservations, reservationCount));
        }
    }

    private boolean conflicts(long start, long end) {
        for (HierarchicalScheduler node = this; node != null; node = node.parent) {
            if (node.hasOverlap(start, end)) {
                return true;
            }
        }

        HierarchicalScheduler[] pending = new HierarchicalScheduler[8];
        int pendingCount = 0;
        for (HierarchicalScheduler child = firstChild;
             child != null;
             child = child.nextSibling) {
            if (pendingCount == pending.length) {
                pending = grow(pending);
            }
            pending[pendingCount++] = child;
        }

        while (pendingCount > 0) {
            HierarchicalScheduler node = pending[--pendingCount];
            if (node.hasOverlap(start, end)) {
                return true;
            }
            for (HierarchicalScheduler child = node.firstChild;
                 child != null;
                 child = child.nextSibling) {
                if (pendingCount == pending.length) {
                    pending = grow(pending);
                }
                pending[pendingCount++] = child;
            }
        }
        return false;
    }

    private boolean hasOverlap(long start, long end) {
        for (ReservationEntry entry = firstReservation;
             entry != null;
             entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private static String escapeSegment(String value) {
        int escapedLength = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\') {
                escapedLength++;
            }
        }

        char[] chars = new char[escapedLength];
        int index = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\') {
                chars[index++] = '\\';
            }
            chars[index++] = c;
        }
        return new String(chars);
    }

    private static int grownLength(int currentLength) {
        if (currentLength == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Array size limit exceeded");
        }
        return currentLength <= Integer.MAX_VALUE / 2
                ? currentLength * 2
                : Integer.MAX_VALUE;
    }

    private static HierarchicalScheduler[] grow(HierarchicalScheduler[] source) {
        HierarchicalScheduler[] result =
                new HierarchicalScheduler[grownLength(source.length)];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] grow(String[] source) {
        String[] result = new String[grownLength(source.length)];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static DetachedReservation[] grow(DetachedReservation[] source) {
        DetachedReservation[] result =
                new DetachedReservation[grownLength(source.length)];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static String[] copy(String[] source, int length) {
        String[] result = new String[length];
        for (int i = 0; i < length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    private static DetachedReservation[] copy(
            DetachedReservation[] source,
            int length) {
        DetachedReservation[] result = new DetachedReservation[length];
        for (int i = 0; i < length; i++) {
            result[i] = source[i];
        }
        return result;
    }

    public static final class DetachedReservation {
        public final String path;
        public final long id;
        public final long start;
        public final long end;

        private DetachedReservation(String path, long id, long start, long end) {
            this.path = path;
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Snapshot {
        private final String[] nodePaths;
        private final DetachedReservation[] reservations;

        private Snapshot(String[] nodePaths, DetachedReservation[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }

        public int nodeCount() {
            return nodePaths.length;
        }

        public String nodePathAt(int index) {
            return nodePaths[index];
        }

        public String[] getNodePaths() {
            return copy(nodePaths, nodePaths.length);
        }

        public int reservationCount() {
            return reservations.length;
        }

        public DetachedReservation reservationAt(int index) {
            return reservations[index];
        }

        public DetachedReservation[] getReservations() {
            return copy(reservations, reservations.length);
        }
    }
}
