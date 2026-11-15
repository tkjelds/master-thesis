@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}


    /** Detached reservation. */
    public static final class Reservation {
        private final long id;
        private final long start;
        private final long end;

        public Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long id() { return id; }
        public long start() { return start; }
        public long end() { return end; }

        @Override
        public String toString() {
            return "Reservation[id=" + id + ", start=" + start + ", end=" + end + "]";
        }
    }

    /** Detached view of one node: its path and its reservations. */
    public static final class NodeSnapshot {
        private final String path;
        private final Reservation[] reservations;

        NodeSnapshot(String path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }

        public String path() { return path; }

        public Reservation[] reservations() {
            return reservations.clone();
        }
    }

    /** Detached view of a subtree. */
    public static final class Snapshot {
        private final NodeSnapshot[] nodes;

        Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }

        public NodeSnapshot[] nodes() {
            return nodes.clone();
        }
    }

    /** Tree-wide shared state; its monitor is the single coordinator lock. */
    private static final class Shared {
        long nextId = 1; // guarded by the Shared monitor
    }

    private final Shared shared;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    // All mutable fields below are guarded by synchronized(shared).
    private HierarchicalScheduler[] children = new HierarchicalScheduler[2];
    private int childCount = 0;
    private long[] starts = new long[4];
    private long[] ends = new long[4];
    private long[] ids = new long[4];
    private int resCount = 0;

    public HierarchicalScheduler() {
        this.shared = new Shared();
        this.parent = null;
        this.name = "";
        this.path = "/";
    }

    private HierarchicalScheduler(HierarchicalScheduler parent, String name) {
        this.shared = parent.shared;
        this.parent = parent;
        this.name = name;
        this.path = parent.parent == null ? "/" + name : parent.path + "/" + name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        synchronized (shared) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }
            HierarchicalScheduler c = new HierarchicalScheduler(this, name);
            if (childCount == children.length) {
                HierarchicalScheduler[] n = new HierarchicalScheduler[childCount * 2];
                for (int i = 0; i < childCount; i++) n[i] = children[i];
                children = n;
            }
            children[childCount++] = c;
            return c;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be < end");
        }
        synchronized (shared) {
            // ancestors
            for (HierarchicalScheduler a = parent; a != null; a = a.parent) {
                if (a.conflicts(start, end)) return -1;
            }
            // this node and all descendants (iterative DFS)
            HierarchicalScheduler[] stack = new HierarchicalScheduler[16];
            int sp = 0;
            stack[sp++] = this;
            while (sp > 0) {
                HierarchicalScheduler n = stack[--sp];
                if (n.conflicts(start, end)) return -1;
                if (sp + n.childCount > stack.length) {
                    int cap = stack.length;
                    while (cap < sp + n.childCount) cap *= 2;
                    HierarchicalScheduler[] ns = new HierarchicalScheduler[cap];
                    for (int i = 0; i < sp; i++) ns[i] = stack[i];
                    stack = ns;
                }
                for (int i = 0; i < n.childCount; i++) stack[sp++] = n.children[i];
            }
            long id = shared.nextId++;
            if (resCount == starts.length) {
                int cap = resCount * 2;
                long[] s2 = new long[cap];
                long[] e2 = new long[cap];
                long[] i2 = new long[cap];
                for (int i = 0; i < resCount; i++) {
                    s2[i] = starts[i];
                    e2[i] = ends[i];
                    i2[i] = ids[i];
                }
                starts = s2;
                ends = e2;
                ids = i2;
            }
            starts[resCount] = start;
            ends[resCount] = end;
            ids[resCount] = id;
            resCount++;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (shared) {
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
        synchronized (shared) {
            NodeSnapshot[] out = new NodeSnapshot[8];
            int outCount = 0;
            HierarchicalScheduler[] stack = new HierarchicalScheduler[16];
            int sp = 0;
            stack[sp++] = this;
            while (sp > 0) {
                HierarchicalScheduler n = stack[--sp];
                Reservation[] rs = new Reservation[n.resCount];
                for (int i = 0; i < n.resCount; i++) {
                    rs[i] = new Reservation(n.ids[i], n.starts[i], n.ends[i]);
                }
                if (outCount == out.length) {
                    NodeSnapshot[] no = new NodeSnapshot[outCount * 2];
                    for (int i = 0; i < outCount; i++) no[i] = out[i];
                    out = no;
                }
                out[outCount++] = new NodeSnapshot(n.path, rs);
                if (sp + n.childCount > stack.length) {
                    int cap = stack.length;
                    while (cap < sp + n.childCount) cap *= 2;
                    HierarchicalScheduler[] ns = new HierarchicalScheduler[cap];
                    for (int i = 0; i < sp; i++) ns[i] = stack[i];
                    stack = ns;
                }
                for (int i = n.childCount - 1; i >= 0; i--) stack[sp++] = n.children[i];
            }
            NodeSnapshot[] result = new NodeSnapshot[outCount];
            for (int i = 0; i < outCount; i++) result[i] = out[i];
            return new Snapshot(result);
        }
    }

    // Caller must hold the shared monitor.
    private boolean conflicts(long s, long e) {
        for (int i = 0; i < resCount; i++) {
            if (starts[i] < e && s < ends[i]) return true;
        }
        return false;
    }
}
