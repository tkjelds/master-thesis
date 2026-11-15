@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final long MAX_ID = 9223372036854775807L;

    private static final class Coordinator {
        long lastId;
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

    /** Detached reservation value. */
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

    /** Detached node data. The root's path is an empty array of segments. */
    public static final class NodeSnapshot {
        public final String[] path;
        public final Reservation[] reservations;

        private NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }
    }

    /**
     * Detached subtree data, including empty nodes.
     * Paths are absolute from the tree root. Nodes are in preorder, with
     * siblings ordered by creation. Reservations are newest first.
     * Mutating these arrays cannot affect the scheduler or another snapshot.
     */
    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;

    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler lastChild;
    private HierarchicalScheduler nextSibling;
    private Entry reservations;

    /** Creates an independent, initially empty reservation tree. */
    public HierarchicalScheduler() {
        coordinator = new Coordinator();
        parent = null;
        name = null;
    }

    private HierarchicalScheduler(HierarchicalScheduler parent, String name) {
        this.coordinator = parent.coordinator;
        this.parent = parent;
        this.name = name;
    }

    /**
     * Returns the unique child with this exact, nonempty name.
     * Names are path segments; no trimming or separator interpretation occurs.
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

            HierarchicalScheduler child = new HierarchicalScheduler(this, name);
            if (lastChild == null) {
                firstChild = child;
            } else {
                lastChild.nextSibling = child;
            }
            lastChild = child;
            return child;
        }
    }

    /**
     * Commits immediately after checking for conflicts, or returns -1.
     * Operations acquire the coordinator monitor but never wait for an
     * interval to become available. Storage grows as needed; exhausting the
     * positive ID space throws IllegalStateException without committing.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must satisfy start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler ancestor = parent;
                 ancestor != null;
                 ancestor = ancestor.parent) {
                if (hasOverlap(ancestor, start, end)) {
                    return -1L;
                }
            }

            for (HierarchicalScheduler node = this;
                 node != null;
                 node = nextInSubtree(node, this)) {
                if (hasOverlap(node, start, end)) {
                    return -1L;
                }
            }

            if (coordinator.lastId == MAX_ID) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            long id = coordinator.lastId + 1L;
            Entry entry = new Entry(id, start, end, reservations);
            reservations = entry;
            coordinator.lastId = id;
            return id;
        }
    }

    /** Removes the reservation only if this node owns it. */
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

    /** Copies the complete subtree while holding the tree-wide monitor. */
    public Snapshot snapshot() {
        synchronized (coordinator) {
            int count = 0;
            for (HierarchicalScheduler node = this;
                 node != null;
                 node = nextInSubtree(node, this)) {
                if (count == 2147483647) {
                    throw new IllegalStateException("Snapshot has too many nodes");
                }
                count++;
            }

            NodeSnapshot[] nodes = new NodeSnapshot[count];
            int index = 0;
            for (HierarchicalScheduler node = this;
                 node != null;
                 node = nextInSubtree(node, this)) {
                nodes[index++] = new NodeSnapshot(
                    copyPath(node), copyReservations(node)
                );
            }
            return new Snapshot(nodes);
        }
    }

    // Private traversal helpers are called only with the coordinator held.
    private static boolean hasOverlap(
        HierarchicalScheduler node, long start, long end
    ) {
        for (Entry entry = node.reservations;
             entry != null;
             entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    // Iterative traversal avoids consuming stack space for deep trees.
    private static HierarchicalScheduler nextInSubtree(
        HierarchicalScheduler node, HierarchicalScheduler subtreeRoot
    ) {
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

    private static String[] copyPath(HierarchicalScheduler node) {
        int depth = 0;
        for (HierarchicalScheduler current = node;
             current.parent != null;
             current = current.parent) {
            if (depth == 2147483647) {
                throw new IllegalStateException("Node path is too deep");
            }
            depth++;
        }

        String[] path = new String[depth];
        for (HierarchicalScheduler current = node;
             current.parent != null;
             current = current.parent) {
            path[--depth] = current.name;
        }
        return path;
    }

    private static Reservation[] copyReservations(HierarchicalScheduler node) {
        int count = 0;
        for (Entry entry = node.reservations;
             entry != null;
             entry = entry.next) {
            if (count == 2147483647) {
                throw new IllegalStateException("Too many reservations to snapshot");
            }
            count++;
        }

        Reservation[] copy = new Reservation[count];
        int index = 0;
        for (Entry entry = node.reservations;
             entry != null;
             entry = entry.next) {
            copy[index++] = new Reservation(entry.id, entry.start, entry.end);
        }
        return copy;
    }
}
