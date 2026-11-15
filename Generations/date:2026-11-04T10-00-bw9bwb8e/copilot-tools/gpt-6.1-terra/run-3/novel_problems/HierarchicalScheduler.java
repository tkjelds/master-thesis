@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        private long nextId = 1;
    }

    private static final class Entry {
        private final long id;
        private final long start;
        private final long end;
        private Entry next;

        private Entry(long id, long start, long end, Entry next) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.next = next;
        }
    }

    /** An immutable, detached reservation. */
    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    /** A detached node and its reservations. */
    public static final class NodeSnapshot {
        private final String[] path;
        private final Reservation[] reservations;

        private NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }

        /**
         * Returns the absolute path as child-name segments. The root has an
         * empty path, so names may contain any characters without ambiguity.
         */
        public String[] path() {
            String[] copy = new String[path.length];
            for (int i = 0; i < path.length; i++) {
                copy[i] = path[i];
            }
            return copy;
        }

        public Reservation[] reservations() {
            Reservation[] copy = new Reservation[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
        }
    }

    /** A detached observation of every node in a subtree. */
    public static final class Snapshot {
        private final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }

        public NodeSnapshot[] nodes() {
            NodeSnapshot[] copy = new NodeSnapshot[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                copy[i] = nodes[i];
            }
            return copy;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final HierarchicalScheduler nextSibling;

    // Access to these fields is guarded by coordinator.
    private HierarchicalScheduler firstChild;
    private Entry reservations;

    /** Creates the root of a new, independent reservation tree. */
    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
        this.nextSibling = null;
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
            HierarchicalScheduler parent,
            String name,
            HierarchicalScheduler nextSibling) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.nextSibling = nextSibling;
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

            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, firstChild);
            firstChild = child;
            return child;
        }
    }

    /**
     * Attempts to reserve [start, end). Returns -1 on conflict without
     * waiting for availability. Throws IllegalStateException if the tree has
     * exhausted its positive reservation IDs.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = node.parent) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler node = nextInSubtree(this, this);
                    node != null;
                    node = nextInSubtree(node, this)) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
            }

            long id = coordinator.nextId;
            if (id == 0) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            Entry entry = new Entry(id, start, end, reservations);
            reservations = entry;
            coordinator.nextId = (id == 9223372036854775807L) ? 0 : id + 1;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            Entry previous = null;
            Entry current = reservations;

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
            int nodeCount = 0;
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                if (nodeCount == 2147483647) {
                    throw new IllegalStateException("Subtree is too large to snapshot");
                }
                nodeCount++;
            }

            NodeSnapshot[] nodes = new NodeSnapshot[nodeCount];
            int index = 0;
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                nodes[index++] = node.copyNodeSnapshot();
            }
            return new Snapshot(nodes);
        }
    }

    // The following helpers are called only while holding coordinator.
    private boolean hasOverlap(long start, long end) {
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    private NodeSnapshot copyNodeSnapshot() {
        int depth = 0;
        for (HierarchicalScheduler node = this;
                node.parent != null;
                node = node.parent) {
            if (depth == 2147483647) {
                throw new IllegalStateException("Path is too large to snapshot");
            }
            depth++;
        }

        String[] path = new String[depth];
        HierarchicalScheduler node = this;
        for (int i = depth - 1; i >= 0; i--) {
            path[i] = node.name;
            node = node.parent;
        }

        int count = 0;
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            if (count == 2147483647) {
                throw new IllegalStateException("Too many reservations to snapshot");
            }
            count++;
        }

        Reservation[] copies = new Reservation[count];
        int index = 0;
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            copies[index++] = new Reservation(entry.id, entry.start, entry.end);
        }
        return new NodeSnapshot(path, copies);
    }

    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler node, HierarchicalScheduler root) {
        if (node.firstChild != null) {
            return node.firstChild;
        }
        while (node != root) {
            if (node.nextSibling != null) {
                return node.nextSibling;
            }
            node = node.parent;
        }
        return null;
    }
}
