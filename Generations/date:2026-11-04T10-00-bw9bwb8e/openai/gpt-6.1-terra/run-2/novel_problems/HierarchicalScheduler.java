@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
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
        // Absolute path as child names from the root; the root path is empty.
        public final String[] path;
        public final Reservation[] reservations;

        private NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }
    }

    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;

    // Accessed only while holding coordinator's monitor.
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private Entry reservations;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
    }

    private HierarchicalScheduler(
            Coordinator coordinator, HierarchicalScheduler parent, String name) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator) {
            HierarchicalScheduler current = firstChild;
            while (current != null) {
                if (current.name.equals(name)) {
                    return current;
                }
                current = current.nextSibling;
            }

            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name);
            created.nextSibling = firstChild;
            firstChild = created;
            return created;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator) {
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
                node = nextInSubtree(node);
            }

            // Positive long IDs cannot be reused. Exhaustion is reported
            // rather than returning the conflict sentinel or wrapping.
            long id = coordinator.nextId;
            if (id == 0) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            Entry entry = new Entry(id, start, end, reservations);
            coordinator.nextId = (id == Long.MAX_VALUE) ? 0 : id + 1;
            reservations = entry;
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
            HierarchicalScheduler node = this;
            while (node != null) {
                nodeCount++;
                node = nextInSubtree(node);
            }

            NodeSnapshot[] nodes = new NodeSnapshot[nodeCount];
            node = this;
            int index = 0;
            while (node != null) {
                int reservationCount = 0;
                Entry entry = node.reservations;
                while (entry != null) {
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

                nodes[index++] = new NodeSnapshot(node.copyPath(), copies);
                node = nextInSubtree(node);
            }
            return new Snapshot(nodes);
        }
    }

    // Called only while holding the coordinator monitor.
    private boolean hasOverlap(long start, long end) {
        Entry current = reservations;
        while (current != null) {
            if (start < current.end && current.start < end) {
                return true;
            }
            current = current.next;
        }
        return false;
    }

    // Returns the next node in a preorder traversal rooted at this node.
    // Called only while holding the coordinator monitor.
    private HierarchicalScheduler nextInSubtree(HierarchicalScheduler node) {
        if (node.firstChild != null) {
            return node.firstChild;
        }
        while (node != this && node.nextSibling == null) {
            node = node.parent;
        }
        return node == this ? null : node.nextSibling;
    }

    // Called only while holding the coordinator monitor.
    private String[] copyPath() {
        int depth = 0;
        HierarchicalScheduler node = this;
        while (node.parent != null) {
            depth++;
            node = node.parent;
        }

        String[] path = new String[depth];
        node = this;
        for (int i = depth - 1; i >= 0; i--) {
            path[i] = node.name;
            node = node.parent;
        }
        return path;
    }
}
