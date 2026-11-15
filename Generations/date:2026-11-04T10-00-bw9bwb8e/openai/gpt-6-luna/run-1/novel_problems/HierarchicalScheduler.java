@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String fullPath;

    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private ReservationEntry firstReservation;

    /**
     * Creates a root scheduler.
     */
    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
        this.fullPath = "/";
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
            HierarchicalScheduler parent,
            String name,
            String fullPath) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.fullPath = fullPath;
    }

    /**
     * Returns this node's child with the given name, creating it if necessary.
     */
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

            String escapedName = escapePathSegment(name);
            String path;
            if (parent == null && this == coordinator.root) {
                path = "/" + escapedName;
            } else {
                path = fullPath + "/" + escapedName;
            }

            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name, path);
            created.nextSibling = firstChild;
            firstChild = created;
            return created;
        }
    }

    /**
     * Reserves the half-open interval [start, end), or returns -1 if it conflicts.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must satisfy start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = node.parent) {
                if (hasOverlap(node, start, end)) {
                    return -1L;
                }
            }

            for (HierarchicalScheduler node = firstInSubtree(this);
                    node != null;
                    node = nextInSubtree(this, node)) {
                if (node != this && hasOverlap(node, start, end)) {
                    return -1L;
                }
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation IDs are exhausted");
            }

            long id = coordinator.nextId;
            ReservationEntry entry = new ReservationEntry(id, start, end);
            entry.next = firstReservation;
            firstReservation = entry;

            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId = id + 1L;
            }
            return id;
        }
    }

    /**
     * Cancels a reservation owned by this node.
     */
    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
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

    /**
     * Returns a detached snapshot of this node and its descendants.
     */
    public Snapshot snapshot() {
        synchronized (coordinator) {
            long nodeCount = 0L;
            long reservationCount = 0L;

            for (HierarchicalScheduler node = firstInSubtree(this);
                    node != null;
                    node = nextInSubtree(this, node)) {
                nodeCount++;
                for (ReservationEntry entry = node.firstReservation;
                        entry != null;
                        entry = entry.next) {
                    reservationCount++;
                }
            }

            if (nodeCount > Integer.MAX_VALUE
                    || reservationCount > Integer.MAX_VALUE) {
                throw new IllegalStateException("Snapshot is too large");
            }

            String[] paths = new String[(int) nodeCount];
            Reservation[] reservations =
                    new Reservation[(int) reservationCount];

            int pathIndex = 0;
            int reservationIndex = 0;
            for (HierarchicalScheduler node = firstInSubtree(this);
                    node != null;
                    node = nextInSubtree(this, node)) {
                paths[pathIndex++] = node.fullPath;
                for (ReservationEntry entry = node.firstReservation;
                        entry != null;
                        entry = entry.next) {
                    reservations[reservationIndex++] =
                            new Reservation(
                                    entry.id,
                                    entry.start,
                                    entry.end,
                                    node.fullPath);
                }
            }

            return new Snapshot(paths, reservations);
        }
    }

    private static boolean hasOverlap(
            HierarchicalScheduler node,
            long start,
            long end) {
        for (ReservationEntry entry = node.firstReservation;
                entry != null;
                entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private static HierarchicalScheduler firstInSubtree(
            HierarchicalScheduler root) {
        return root;
    }

    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler root,
            HierarchicalScheduler current) {
        if (current.firstChild != null) {
            return current.firstChild;
        }

        while (current != root) {
            if (current.nextSibling != null) {
                return current.nextSibling;
            }
            current = current.parent;
        }
        return null;
    }

    private static String escapePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                result.append("%25");
            } else if (c == '/') {
                result.append("%2F");
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static final class Coordinator {
        private final HierarchicalScheduler root;
        private long nextId = 1L;
        private boolean idsExhausted;

        private Coordinator() {
            this.root = null;
        }

        private Coordinator(HierarchicalScheduler root) {
            this.root = root;
        }
    }

    private static final class ReservationEntry {
        private final long id;
        private final long start;
        private final long end;
        private ReservationEntry next;

        private ReservationEntry(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Detached reservation data returned as part of a snapshot.
     */
    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;
        public final String path;

        private Reservation(long id, long start, long end, String path) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.path = path;
        }
    }

    /**
     * Detached node paths and reservations from one coherent observation.
     */
    public static final class Snapshot {
        public final String[] nodePaths;
        public final Reservation[] reservations;

        private Snapshot(String[] nodePaths, Reservation[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }
    }
}
