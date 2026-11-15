@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}


    /** Detached, immutable reservation. */
    public record Reservation(long id, long start, long end) {
    }

    /** Detached view of one node: its path and its reservations. */
    public record NodeSnapshot(String path, Reservation[] reservations) {
        public NodeSnapshot {
            reservations = reservations.clone();
        }

        @Override
        public Reservation[] reservations() {
            return reservations.clone();
        }
    }

    /** Detached view of a subtree in pre-order (this node first, children in creation order). */
    public record Snapshot(NodeSnapshot[] nodes) {
        public Snapshot {
            nodes = nodes.clone();
        }

        @Override
        public NodeSnapshot[] nodes() {
            return nodes.clone();
        }
    }

    /** Tree-wide coordinator: its monitor guards all mutable state in the tree. */
    private static final class Tree {
        long nextId = 1;
    }

    private final Tree tree;
    private final HierarchicalScheduler parent;
    private final String path;

    // All fields below are guarded by tree's monitor.
    private long[] starts = new long[4];
    private long[] ends = new long[4];
    private long[] ids = new long[4];
    private int count = 0;
    private String[] kidNames = new String[4];
    private HierarchicalScheduler[] kids = new HierarchicalScheduler[4];
    private int kidCount = 0;

    /** Creates a root node. Root path is "/". */
    public HierarchicalScheduler() {
        this.tree = new Tree();
        this.parent = null;
        this.path = "/";
    }

    private HierarchicalScheduler(HierarchicalScheduler parent, String name) {
        this.tree = parent.tree;
        this.parent = parent;
        this.path = parent.parent == null ? "/" + name : parent.path + "/" + name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        synchronized (tree) {
            for (int i = 0; i < kidCount; i++) {
                if (kidNames[i].equals(name)) {
                    return kids[i];
                }
            }
            HierarchicalScheduler c = new HierarchicalScheduler(this, name);
            if (kidCount == kids.length) {
                int n = kidCount * 2;
                String[] nn = new String[n];
                HierarchicalScheduler[] nk = new HierarchicalScheduler[n];
                System.arraycopy(kidNames, 0, nn, 0, kidCount);
                System.arraycopy(kids, 0, nk, 0, kidCount);
                kidNames = nn;
                kids = nk;
            }
            kidNames[kidCount] = name;
            kids[kidCount] = c;
            kidCount++;
            return c;
        }
    }

    /** Returns a positive unique id, or -1 if the interval conflicts. */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("start must be < end");
        }
        synchronized (tree) {
            // this node and ancestors
            for (HierarchicalScheduler n = this; n != null; n = n.parent) {
                if (n.overlaps(start, end)) {
                    return -1;
                }
            }
            // descendants (excluding this, already checked)
            HierarchicalScheduler[] stack = new HierarchicalScheduler[16];
            int sp = 0;
            for (int i = 0; i < kidCount; i++) {
                if (sp == stack.length) stack = grow(stack);
                stack[sp++] = kids[i];
            }
            while (sp > 0) {
                HierarchicalScheduler n = stack[--sp];
                if (n.overlaps(start, end)) {
                    return -1;
                }
                for (int i = 0; i < n.kidCount; i++) {
                    if (sp == stack.length) stack = grow(stack);
                    stack[sp++] = n.kids[i];
                }
            }
            long id = tree.nextId++;
            if (count == starts.length) {
                int n = count * 2;
                long[] s = new long[n];
                long[] e = new long[n];
                long[] d = new long[n];
                System.arraycopy(starts, 0, s, 0, count);
                System.arraycopy(ends, 0, e, 0, count);
                System.arraycopy(ids, 0, d, 0, count);
                starts = s;
                ends = e;
                ids = d;
            }
            starts[count] = start;
            ends[count] = end;
            ids[count] = id;
            count++;
            return id;
        }
    }

    /** Cancels a reservation owned by this node. */
    public boolean cancel(long reservationId) {
        synchronized (tree) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == reservationId) {
                    int tail = count - i - 1;
                    if (tail > 0) {
                        System.arraycopy(starts, i + 1, starts, i, tail);
                        System.arraycopy(ends, i + 1, ends, i, tail);
                        System.arraycopy(ids, i + 1, ids, i, tail);
                    }
                    count--;
                    return true;
                }
            }
            return false;
        }
    }

    /** Coherent snapshot of this subtree (absolute paths; root is "/"). */
    public Snapshot snapshot() {
        synchronized (tree) {
            HierarchicalScheduler[] stack = new HierarchicalScheduler[16];
            NodeSnapshot[] out = new NodeSnapshot[16];
            int outCount = 0;
            int sp = 0;
            stack[sp++] = this;
            while (sp > 0) {
                HierarchicalScheduler n = stack[--sp];
                Reservation[] rs = new Reservation[n.count];
                for (int i = 0; i < n.count; i++) {
                    rs[i] = new Reservation(n.ids[i], n.starts[i], n.ends[i]);
                }
                if (outCount == out.length) {
                    NodeSnapshot[] bigger = new NodeSnapshot[outCount * 2];
                    System.arraycopy(out, 0, bigger, 0, outCount);
                    out = bigger;
                }
                out[outCount++] = new NodeSnapshot(n.path, rs);
                for (int i = n.kidCount - 1; i >= 0; i--) {
                    if (sp == stack.length) stack = grow(stack);
                    stack[sp++] = n.kids[i];
                }
            }
            NodeSnapshot[] result = new NodeSnapshot[outCount];
            System.arraycopy(out, 0, result, 0, outCount);
            return new Snapshot(result);
        }
    }

    // Caller must hold the tree monitor.
    private boolean overlaps(long start, long end) {
        for (int i = 0; i < count; i++) {
            if (starts[i] < end && start < ends[i]) {
                return true;
            }
        }
        return false;
    }

    private static HierarchicalScheduler[] grow(HierarchicalScheduler[] a) {
        HierarchicalScheduler[] b = new HierarchicalScheduler[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
