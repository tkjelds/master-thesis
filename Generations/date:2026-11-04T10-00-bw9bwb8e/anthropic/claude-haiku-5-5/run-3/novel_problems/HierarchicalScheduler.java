@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}


    /** Immutable, detached reservation record. */
    public static final class Reservation {
        private final long id;
        private final String ownerPath;
        private final long start;
        private final long end;

        Reservation(long id, String ownerPath, long start, long end) {
            this.id = id;
            this.ownerPath = ownerPath;
            this.start = start;
            this.end = end;
        }

        public long getId() {
            return id;
        }

        public String getOwnerPath() {
            return ownerPath;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    /** Immutable, detached snapshot of a subtree. Arrays are copied on access. */
    public static final class Snapshot {
        private final String[] nodePaths;
        private final Reservation[] reservations;

        Snapshot(String[] nodePaths, Reservation[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }

        public String[] getNodePaths() {
            return nodePaths.clone();
        }

        public Reservation[] getReservations() {
            return reservations.clone();
        }
    }

    /** Shared state for one tree: the single coordinator monitor and the ID counter. */
    private static final class Tree {
        final Object lock = new Object();
        long lastId; // guarded by lock
    }

    // All fields below are guarded by tree.lock (except final ones set at construction).
    private final Tree tree;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;
    private HierarchicalScheduler[] children = new HierarchicalScheduler[0];
    private int childCount;
    private Reservation[] reservations = new Reservation[0];
    private int reservationCount;

    /** Creates a root node of a new reservation tree. */
    public HierarchicalScheduler() {
        this.tree = new Tree();
        this.parent = null;
        this.name = "";
        this.path = "/";
    }

    private HierarchicalScheduler(Tree tree, HierarchicalScheduler parent, String name) {
        this.tree = tree;
        this.parent = parent;
        this.name = name;
        this.path = parent.path.equals("/") ? "/" + name : parent.path + "/" + name;
    }

    /**
     * Returns the unique child with the given nonempty name, creating it if needed.
     */
    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("invalid child name");
        }
        synchronized (tree.lock) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }
            HierarchicalScheduler node = new HierarchicalScheduler(tree, this, name);
            if (childCount == children.length) {
                HierarchicalScheduler[] grown = new HierarchicalScheduler[Math.max(4, childCount * 2)];
                System.arraycopy(children, 0, grown, 0, childCount);
                children = grown;
            }
            children[childCount++] = node;
            return node;
        }
    }

    /**
     * Attempts to reserve [start, end). Returns a positive unique ID on success,
     * or -1 if the reservation conflicts with any reservation on this node,
     * an ancestor, or a descendant. Never blocks waiting for availability.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("invalid interval");
        }
        synchronized (tree.lock) {
            if (hasConflict(start, end)) {
                return -1L;
            }
            long id = ++tree.lastId;
            if (reservationCount == reservations.length) {
                Reservation[] grown = new Reservation[Math.max(4, reservationCount * 2)];
                System.arraycopy(reservations, 0, grown, 0, reservationCount);
                reservations = grown;
            }
            reservations[reservationCount++] = new Reservation(id, path, start, end);
            return id;
        }
    }

    /**
     * Removes the reservation with the given ID if it is owned by this node.
     * Returns true if it existed and was removed.
     */
    public boolean cancel(long reservationId) {
        synchronized (tree.lock) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].getId() == reservationId) {
                    System.arraycopy(reservations, i + 1, reservations, i, reservationCount - i - 1);
                    reservations[--reservationCount] = null;
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns a coherent snapshot of this subtree's node paths and reservations.
     */
    public Snapshot snapshot() {
        synchronized (tree.lock) {
            int nodeCount = countNodes();
            int resCount = countReservations();
            String[] paths = new String[nodeCount];
            Reservation[] rs = new Reservation[resCount];
            int[] pi = new int[1];
            int[] ri = new int[1];
            fill(paths, pi, rs, ri);
            return new Snapshot(paths, rs);
        }
    }

    // ---- Helpers: all must be called while holding tree.lock ----

    private boolean hasConflict(long start, long end) {
        for (HierarchicalScheduler p = this; p != null; p = p.parent) {
            if (p.overlapsLocal(start, end)) {
                return true;
            }
        }
        return descendantConflict(start, end);
    }

    private boolean overlapsLocal(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            Reservation r = reservations[i];
            if (r.getStart() < end && start < r.getEnd()) {
                return true;
            }
        }
        return false;
    }

    private boolean descendantConflict(long start, long end) {
        for (int i = 0; i < childCount; i++) {
            HierarchicalScheduler c = children[i];
            if (c.overlapsLocal(start, end) || c.descendantConflict(start, end)) {
                return true;
            }
        }
        return false;
    }

    private int countNodes() {
        int n = 1;
        for (int i = 0; i < childCount; i++) {
            n += children[i].countNodes();
        }
        return n;
    }

    private int countReservations() {
        int n = reservationCount;
        for (int i = 0; i < childCount; i++) {
            n += children[i].countReservations();
        }
        return n;
    }

    private void fill(String[] paths, int[] pi, Reservation[] rs, int[] ri) {
        paths[pi[0]++] = path;
        for (int i = 0; i < reservationCount; i++) {
            Reservation r = reservations[i];
            rs[ri[0]++] = new Reservation(r.getId(), r.getOwnerPath(), r.getStart(), r.getEnd());
        }
        for (int i = 0; i < childCount; i++) {
            children[i].fill(paths, pi, rs, ri);
        }
    }
}
