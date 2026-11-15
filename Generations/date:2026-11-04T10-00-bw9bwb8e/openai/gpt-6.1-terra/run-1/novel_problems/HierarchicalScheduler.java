@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final long MAX_ID = 0x7fffffffffffffffL;

    private static final class Coordinator {
        final Object monitor = new Object();
        long nextId = 1;
    }

    private static final class Entry {
        final long id;
        final long start;
        final long end;
        Entry next;

        Entry(long id, long start, long end, Entry next) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.next = next;
        }
    }

    /** An immutable copy of a reservation. */
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

    /** A detached view of one node in a snapshot. */
    public static final class NodeSnapshot {
        public final String path;
        public final Reservation[] reservations;

        private NodeSnapshot(String path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }
    }

    /** The nodes and reservations observed in a single subtree snapshot. */
    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    // These fields are accessed only while holding coordinator.monitor.
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private Entry reservations;

    /** Creates the root of a new, independent reservation tree. */
    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
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

    /**
     * Returns the unique child with the given nonempty name.
     * Paths use "/" as the root; in each name, "~" is encoded as "~0"
     * and "/" as "~1".
     */
    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator.monitor) {
            HierarchicalScheduler current = firstChild;
            while (current != null) {
                if (current.name.equals(name)) {
                    return current;
                }
                current = current.nextSibling;
            }

            String encoded = name.replace("~", "~0").replace("/", "~1");
            String childPath = parent == null
                    ? "/" + encoded
                    : path + "/" + encoded;
            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name, childPath);
            created.nextSibling = firstChild;
            firstChild = created;
            return created;
        }
    }

    /**
     * Reserves [start, end), or returns -1 if it conflicts.
     * This operation never waits for availability. If all positive long IDs
     * have been used, it throws IllegalStateException rather than reuse an ID.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must be nonempty");
        }

        synchronized (coordinator.monitor) {
            HierarchicalScheduler ancestor = parent;
            while (ancestor != null) {
                if (ancestor.hasOverlap(start, end)) {
                    return -1;
                }
                ancestor = ancestor.parent;
            }

            HierarchicalScheduler node = this;
            while (node != null) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
                node = nextInSubtree(this, node);
            }

            long id = coordinator.nextId;
            if (id == 0) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            Entry entry = new Entry(id, start, end, reservations);
            coordinator.nextId = id == MAX_ID ? 0 : id + 1;
            reservations = entry;
            return id;
        }
    }

    /** Removes a reservation owned by this node, if it exists. */
    public boolean cancel(long reservationId) {
        synchronized (coordinator.monitor) {
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

    /**
     * Returns detached arrays and immutable reservation records for this
     * subtree, observed while holding the tree-wide monitor.
     */
    public Snapshot snapshot() {
        synchronized (coordinator.monitor) {
            int nodeCount = 0;
            HierarchicalScheduler node = this;
            while (node != null) {
                if (nodeCount == 0x7fffffff) {
                    throw new IllegalStateException("Too many nodes to snapshot");
                }
                nodeCount++;
                node = nextInSubtree(this, node);
            }

            NodeSnapshot[] nodes = new NodeSnapshot[nodeCount];
            node = this;
            int nodeIndex = 0;
            while (node != null) {
                int reservationCount = 0;
                Entry entry = node.reservations;
                while (entry != null) {
                    if (reservationCount == 0x7fffffff) {
                        throw new IllegalStateException(
                                "Too many reservations to snapshot");
                    }
                    reservationCount++;
                    entry = entry.next;
                }

                Reservation[] copies = new Reservation[reservationCount];
                entry = node.reservations;
                int reservationIndex = 0;
                while (entry != null) {
                    copies[reservationIndex++] =
                            new Reservation(entry.id, entry.start, entry.end);
                    entry = entry.next;
                }

                nodes[nodeIndex++] = new NodeSnapshot(node.path, copies);
                node = nextInSubtree(this, node);
            }
            return new Snapshot(nodes);
        }
    }

    // Called only while holding the tree-wide monitor.
    private boolean hasOverlap(long start, long end) {
        Entry entry = reservations;
        while (entry != null) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
            entry = entry.next;
        }
        return false;
    }

    // Preorder traversal without recursion or leaving the specified subtree.
    // Called only while holding the tree-wide monitor.
    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler subtreeRoot, HierarchicalScheduler current) {
        if (current.firstChild != null) {
            return current.firstChild;
        }
        while (current != subtreeRoot && current.nextSibling == null) {
            current = current.parent;
        }
        return current == subtreeRoot ? null : current.nextSibling;
    }
}
