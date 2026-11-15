@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        // Zero means that all positive long IDs have been exhausted.
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

    public static final class NodeSnapshot {
        /**
         * Absolute path components from the tree root.
         * The root has an empty path. Names are not split on separators.
         * This array is detached from the scheduler.
         */
        public final String[] path;

        /** Detached reservations owned directly by this node. */
        public final Reservation[] reservations;

        private NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }
    }

    public static final class Snapshot {
        /** Detached nodes in subtree preorder, including the requested node. */
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final HierarchicalScheduler nextSibling;

    // Mutable tree state is accessed only under the coordinator monitor.
    private HierarchicalScheduler firstChild;
    private Entry reservations;

    public HierarchicalScheduler() {
        coordinator = new Coordinator();
        parent = null;
        name = null;
        nextSibling = null;
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
     * Returns a positive reservation ID, or -1 on conflict.
     * Does not wait for conflicts to disappear.
     *
     * @throws IllegalStateException if positive long IDs are exhausted
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must satisfy start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = node.parent) {
                if (node.overlapsLocked(start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler node = firstChild;
                    node != null;
                    node = nextInSubtreeLocked(node, this)) {
                if (node.overlapsLocked(start, end)) {
                    return -1;
                }
            }

            long id = coordinator.nextId;
            if (id == 0) {
                throw new IllegalStateException("Reservation IDs are exhausted");
            }

            Entry entry = new Entry(id, start, end, reservations);
            coordinator.nextId = id == Long.MAX_VALUE ? 0 : id + 1;
            reservations = entry;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            Entry previous = null;
            Entry entry = reservations;

            while (entry != null) {
                if (entry.id == reservationId) {
                    if (previous == null) {
                        reservations = entry.next;
                    } else {
                        previous.next = entry.next;
                    }
                    return true;
                }
                previous = entry;
                entry = entry.next;
            }

            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator) {
            int count = 0;
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtreeLocked(node, this)) {
                if (count == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Too many nodes for a snapshot");
                }
                count++;
            }

            NodeSnapshot[] nodes = new NodeSnapshot[count];
            int index = 0;

            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtreeLocked(node, this)) {
                nodes[index++] = new NodeSnapshot(
                        node.copyPathLocked(), node.copyReservationsLocked());
            }

            return new Snapshot(nodes);
        }
    }

    private boolean overlapsLocked(long start, long end) {
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    // Iterative traversal avoids a call-stack limit on tree depth.
    private static HierarchicalScheduler nextInSubtreeLocked(
            HierarchicalScheduler node, HierarchicalScheduler subtreeRoot) {
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

    private String[] copyPathLocked() {
        int depth = 0;
        for (HierarchicalScheduler node = this;
                node.parent != null;
                node = node.parent) {
            if (depth == Integer.MAX_VALUE) {
                throw new IllegalStateException("Path is too deep for a snapshot");
            }
            depth++;
        }

        String[] path = new String[depth];
        HierarchicalScheduler node = this;
        while (depth > 0) {
            path[--depth] = node.name;
            node = node.parent;
        }
        return path;
    }

    private Reservation[] copyReservationsLocked() {
        int count = 0;
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "Too many reservations for a snapshot");
            }
            count++;
        }

        Reservation[] copy = new Reservation[count];
        int index = 0;
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            copy[index++] = new Reservation(entry.id, entry.start, entry.end);
        }
        return copy;
    }
}
