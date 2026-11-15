@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}


    /** Shared tree-wide coordinator. Its intrinsic monitor guards every node in the tree. */
    private static final class Coordinator {
        long nextId = 1L;
    }

    /** Detached, immutable description of one committed reservation. */
    public static final class ReservationInfo {
        private final String nodePath;
        private final long id;
        private final long start;
        private final long end;

        private ReservationInfo(String nodePath, long id, long start, long end) {
            this.nodePath = nodePath;
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public String getNodePath() { return nodePath; }
        public long getId() { return id; }
        public long getStart() { return start; }
        public long getEnd() { return end; }
    }

    /** Detached, immutable snapshot of a subtree's node paths and reservations. */
    public static final class Snapshot {
        private final String[] nodePaths;
        private final ReservationInfo[] reservations;

        private Snapshot(String[] nodePaths, ReservationInfo[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }

        /** Returns a fresh copy of the node paths (subtree root first, then DFS order). */
        public String[] getNodePaths() {
            return nodePaths.clone();
        }

        /** Returns a fresh copy of the reservation records. */
        public ReservationInfo[] getReservations() {
            return reservations.clone();
        }
    }

    private final Coordinator coord;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    // All fields below are guarded by coord.
    private HierarchicalScheduler[] children = new HierarchicalScheduler[4];
    private int childCount;

    private long[] resStart = new long[4];
    private long[] resEnd = new long[4];
    private long[] resId = new long[4];
    private int resCount;

    /** Creates a root scheduler with its own tree-wide coordinator. */
    public HierarchicalScheduler() {
        this.coord = new Coordinator();
        this.parent = null;
        this.name = "";
        this.path = "/";
    }

    /** Creates a child node; must be called while holding coord's monitor. */
    private HierarchicalScheduler(HierarchicalScheduler parent, String name, String path) {
        this.coord = parent.coord;
        this.parent = parent;
        this.name = name;
        this.path = path;
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("invalid node name: " + name);
        }
    }

    /**
     * Returns the unique child with the given name, creating it if necessary.
     */
    public HierarchicalScheduler child(String name) {
        validateName(name);
        synchronized (coord) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }
            String childPath = path.equals("/") ? "/" + name : path + "/" + name;
            HierarchicalScheduler created = new HierarchicalScheduler(this, name, childPath);
            if (childCount == children.length) {
                HierarchicalScheduler[] grown = new HierarchicalScheduler[children.length * 2];
                System.arraycopy(children, 0, grown, 0, childCount);
                children = grown;
            }
            children[childCount++] = created;
            return created;
        }
    }

    /**
     * Attempts to reserve [start, end). Returns a positive unique ID on success,
     * or -1 on conflict. Never waits for availability.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("invalid interval: [" + start + ", " + end + ")");
        }
        synchronized (coord) {
            for (HierarchicalScheduler n = this; n != null; n = n.parent) {
                if (n.hasOverlapLocal(start, end)) {
                    return -1L;
                }
            }
            if (hasOverlapInSubtree(start, end)) {
                return -1L;
            }
            long id = coord.nextId++;
            appendLocal(start, end, id);
            return id;
        }
    }

    /**
     * Cancels a reservation owned by this node. Returns true if it existed.
     */
    public boolean cancel(long reservationId) {
        synchronized (coord) {
            for (int i = 0; i < resCount; i++) {
                if (resId[i] == reservationId) {
                    int last = resCount - 1;
                    resStart[i] = resStart[last];
                    resEnd[i] = resEnd[last];
                    resId[i] = resId[last];
                    resCount--;
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns a coherent, detached snapshot of this subtree.
     */
    public Snapshot snapshot() {
        synchronized (coord) {
            int[] counts = new int[2]; // [nodes, reservations]
            countSubtree(counts);
            String[] paths = new String[counts[0]];
            ReservationInfo[] infos = new ReservationInfo[counts[1]];
            int[] cursor = new int[2];
            collectSubtree(paths, infos, cursor);
            return new Snapshot(paths, infos);
        }
    }

    // ---- Helpers; all must be called while holding coord's monitor. ----

    private boolean hasOverlapLocal(long start, long end) {
        for (int i = 0; i < resCount; i++) {
            if (start < resEnd[i] && resStart[i] < end) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOverlapInSubtree(long start, long end) {
        if (hasOverlapLocal(start, end)) {
            return true;
        }
        for (int i = 0; i < childCount; i++) {
            if (children[i].hasOverlapInSubtree(start, end)) {
                return true;
            }
        }
        return false;
    }

    private void appendLocal(long start, long end, long id) {
        if (resCount == resStart.length) {
            int newCap = resStart.length * 2;
            long[] s = new long[newCap];
            long[] e = new long[newCap];
            long[] d = new long[newCap];
            System.arraycopy(resStart, 0, s, 0, resCount);
            System.arraycopy(resEnd, 0, e, 0, resCount);
            System.arraycopy(resId, 0, d, 0, resCount);
            resStart = s;
            resEnd = e;
            resId = d;
        }
        resStart[resCount] = start;
        resEnd[resCount] = end;
        resId[resCount] = id;
        resCount++;
    }

    private void countSubtree(int[] counts) {
        counts[0]++;
        counts[1] += resCount;
        for (int i = 0; i < childCount; i++) {
            children[i].countSubtree(counts);
        }
    }

    private void collectSubtree(String[] paths, ReservationInfo[] infos, int[] cursor) {
        paths[cursor[0]++] = path;
        for (int i = 0; i < resCount; i++) {
            infos[cursor[1]++] = new ReservationInfo(path, resId[i], resStart[i], resEnd[i]);
        }
        for (int i = 0; i < childCount; i++) {
            children[i].collectSubtree(paths, infos, cursor);
        }
    }
}
