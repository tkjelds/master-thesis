@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}


    public record Reservation(String nodePath, long id, long start, long end) {
    }

    public record Snapshot(String[] nodePaths, Reservation[] reservations) {
        public Snapshot {
            nodePaths = nodePaths.clone();
            reservations = reservations.clone();
        }

        @Override
        public String[] nodePaths() {
            return nodePaths.clone();
        }

        @Override
        public Reservation[] reservations() {
            return reservations.clone();
        }
    }

    // Tree-wide coordinator: single monitor guarding all tree state.
    private static final class Coordinator {
        long nextId = 1;
    }

    private final Coordinator coord;
    private final HierarchicalScheduler parent;
    private final String path;

    // Guarded by coord
    private HierarchicalScheduler[] children = new HierarchicalScheduler[2];
    private String[] childNames = new String[2];
    private int childCount = 0;

    private long[] starts = new long[4];
    private long[] ends = new long[4];
    private long[] ids = new long[4];
    private int resCount = 0;

    public HierarchicalScheduler() {
        this.coord = new Coordinator();
        this.parent = null;
        this.path = "/";
    }

    private HierarchicalScheduler(Coordinator coord, HierarchicalScheduler parent, String name) {
        this.coord = coord;
        this.parent = parent;
        this.path = parent.parent == null ? "/" + name : parent.path + "/" + name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("invalid name");
        }
        synchronized (coord) {
            for (int i = 0; i < childCount; i++) {
                if (childNames[i].equals(name)) {
                    return children[i];
                }
            }
            if (childCount == children.length) {
                int n = childCount * 2;
                HierarchicalScheduler[] nc = new HierarchicalScheduler[n];
                String[] nn = new String[n];
                for (int i = 0; i < childCount; i++) {
                    nc[i] = children[i];
                    nn[i] = childNames[i];
                }
                children = nc;
                childNames = nn;
            }
            HierarchicalScheduler c = new HierarchicalScheduler(coord, this, name);
            children[childCount] = c;
            childNames[childCount] = name;
            childCount++;
            return c;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be < end");
        }
        synchronized (coord) {
            for (HierarchicalScheduler a = this; a != null; a = a.parent) {
                if (a.overlapsLocal(start, end)) {
                    return -1;
                }
            }
            for (int i = 0; i < childCount; i++) {
                if (children[i].overlapsSubtree(start, end)) {
                    return -1;
                }
            }
            long id = coord.nextId++;
            if (resCount == starts.length) {
                int n = resCount * 2;
                starts = grow(starts, n);
                ends = grow(ends, n);
                ids = grow(ids, n);
            }
            starts[resCount] = start;
            ends[resCount] = end;
            ids[resCount] = id;
            resCount++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coord) {
            for (int i = 0; i < resCount; i++) {
                if (ids[i] == reservationId) {
                    for (int j = i + 1; j < resCount; j++) {
                        starts[j - 1] = starts[j];
                        ends[j - 1] = ends[j];
                        ids[j - 1] = ids[j];
                    }
                    resCount--;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coord) {
            int[] counts = new int[2];
            count(this, counts);
            String[] paths = new String[counts[0]];
            Reservation[] res = new Reservation[counts[1]];
            int[] idx = new int[2];
            collect(this, paths, res, idx);
            return new Snapshot(paths, res);
        }
    }

    // ---- helpers (call only while holding coord) ----

    private static long[] grow(long[] a, int n) {
        long[] r = new long[n];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i];
        }
        return r;
    }

    private boolean overlapsLocal(long start, long end) {
        for (int i = 0; i < resCount; i++) {
            if (starts[i] < end && start < ends[i]) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsSubtree(long start, long end) {
        if (overlapsLocal(start, end)) {
            return true;
        }
        for (int i = 0; i < childCount; i++) {
            if (children[i].overlapsSubtree(start, end)) {
                return true;
            }
        }
        return false;
    }

    private static void count(HierarchicalScheduler n, int[] counts) {
        counts[0]++;
        counts[1] += n.resCount;
        for (int i = 0; i < n.childCount; i++) {
            count(n.children[i], counts);
        }
    }

    private static void collect(HierarchicalScheduler n, String[] paths, Reservation[] res, int[] idx) {
        paths[idx[0]++] = n.path;
        for (int i = 0; i < n.resCount; i++) {
            res[idx[1]++] = new Reservation(n.path, n.ids[i], n.starts[i], n.ends[i]);
        }
        for (int i = 0; i < n.childCount; i++) {
            collect(n.children[i], paths, res, idx);
        }
    }
}
