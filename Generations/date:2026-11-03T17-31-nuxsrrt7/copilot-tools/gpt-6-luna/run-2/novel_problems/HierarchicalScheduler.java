@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String[] pathSegments;

    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;

    private long[] reservationIds = new long[0];
    private long[] reservationStarts = new long[0];
    private long[] reservationEnds = new long[0];
    private int reservationCount;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
        this.pathSegments = new String[0];
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
            HierarchicalScheduler parent,
            String name) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.pathSegments = new String[parent.pathSegments.length + 1];
        System.arraycopy(parent.pathSegments, 0, this.pathSegments, 0, parent.pathSegments.length);
        this.pathSegments[this.pathSegments.length - 1] = name;
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

            HierarchicalScheduler child = new HierarchicalScheduler(coordinator, this, name);
            if (firstChild == null) {
                firstChild = child;
            } else {
                HierarchicalScheduler last = firstChild;
                while (last.nextSibling != null) {
                    last = last.nextSibling;
                }
                last.nextSibling = child;
            }
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must have start < end");
        }

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler node = parent; node != null; node = node.parent) {
                if (node.hasOverlap(start, end)) {
                    return -1L;
                }
            }

            for (HierarchicalScheduler node = this; node != null; node = nextInSubtree(node, this)) {
                if (node.hasOverlap(start, end)) {
                    return -1L;
                }
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }
            if (reservationCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many reservations on this node");
            }

            ensureReservationCapacity(reservationCount + 1);

            long id = coordinator.nextId;
            reservationIds[reservationCount] = id;
            reservationStarts[reservationCount] = start;
            reservationEnds[reservationCount] = end;
            reservationCount++;

            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId = id + 1L;
            }
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator.monitor) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservationIds[i] == reservationId) {
                    int last = reservationCount - 1;
                    reservationIds[i] = reservationIds[last];
                    reservationStarts[i] = reservationStarts[last];
                    reservationEnds[i] = reservationEnds[last];
                    reservationCount = last;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator.monitor) {
            long nodeTotal = 0L;
            long reservationTotal = 0L;

            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                nodeTotal++;
                reservationTotal += node.reservationCount;
            }

            if (nodeTotal > Integer.MAX_VALUE || reservationTotal > Integer.MAX_VALUE) {
                throw new IllegalStateException("Snapshot is too large");
            }

            String[][] nodePaths = new String[(int) nodeTotal][];
            Reservation[] reservations = new Reservation[(int) reservationTotal];
            int nodeIndex = 0;
            int reservationIndex = 0;

            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                nodePaths[nodeIndex++] = copyPath(node.pathSegments);
                for (int i = 0; i < node.reservationCount; i++) {
                    reservations[reservationIndex++] = new Reservation(
                            node.reservationIds[i],
                            node.reservationStarts[i],
                            node.reservationEnds[i],
                            node.pathSegments);
                }
            }

            return new Snapshot(nodePaths, reservations);
        }
    }

    private boolean hasOverlap(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            if (start < reservationEnds[i] && reservationStarts[i] < end) {
                return true;
            }
        }
        return false;
    }

    private void ensureReservationCapacity(int required) {
        if (required <= reservationIds.length) {
            return;
        }

        int oldCapacity = reservationIds.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 4;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }
        if (newCapacity < required) {
            newCapacity = required;
        }

        long[] newIds = new long[newCapacity];
        long[] newStarts = new long[newCapacity];
        long[] newEnds = new long[newCapacity];
        System.arraycopy(reservationIds, 0, newIds, 0, reservationCount);
        System.arraycopy(reservationStarts, 0, newStarts, 0, reservationCount);
        System.arraycopy(reservationEnds, 0, newEnds, 0, reservationCount);
        reservationIds = newIds;
        reservationStarts = newStarts;
        reservationEnds = newEnds;
    }

    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler node,
            HierarchicalScheduler subtreeRoot) {
        if (node.firstChild != null) {
            return node.firstChild;
        }

        while (node != subtreeRoot) {
            if (node.nextSibling != null) {
                return node.nextSibling;
            }
            node = node.parent;
        }
        return null;
    }

    private static String[] copyPath(String[] path) {
        String[] copy = new String[path.length];
        System.arraycopy(path, 0, copy, 0, path.length);
        return copy;
    }

    private static final class Coordinator {
        private final Object monitor = new Object();
        private long nextId = 1L;
        private boolean idsExhausted;
    }

    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;
        private final String[] path;

        private Reservation(long id, long start, long end, String[] path) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.path = copyPath(path);
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

        /** Returns the owner's path as name segments from the tree root. */
        public String[] getPath() {
            return copyPath(path);
        }
    }

    public static final class Snapshot {
        private final String[][] nodePaths;
        private final Reservation[] reservations;

        private Snapshot(String[][] nodePaths, Reservation[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }

        /** Returns node paths as name segments from the tree root; the root path is empty. */
        public String[][] getNodePaths() {
            String[][] copy = new String[nodePaths.length][];
            for (int i = 0; i < nodePaths.length; i++) {
                copy[i] = copyPath(nodePaths[i]);
            }
            return copy;
        }

        /** Returns detached reservation records for this subtree. */
        public Reservation[] getReservations() {
            Reservation[] copy = new Reservation[reservations.length];
            System.arraycopy(reservations, 0, copy, 0, reservations.length);
            return copy;
        }
    }
}
