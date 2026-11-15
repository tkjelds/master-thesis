@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        // Zero means that all positive long IDs have been issued.
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

    /** A detached reservation value. */
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

    /**
     * A detached node value. The path consists of names from the root to
     * this node, excluding the unnamed root.
     */
    public static final class NodeSnapshot {
        public final String[] path;
        public final Reservation[] reservations;

        private NodeSnapshot(String[] path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }
    }

    /** All nodes in the requested subtree, including the requested node. */
    public static final class Snapshot {
        public final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final int depth;

    // Accessed only while holding coordinator's monitor.
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler nextSibling;
    private Entry reservations;

    /** Creates an unnamed root of a new reservation tree. */
    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
        this.depth = 0;
    }

    private HierarchicalScheduler(
            Coordinator coordinator, HierarchicalScheduler parent, String name) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.depth = parent.depth + 1;
    }

    /** Returns the unique child with the given nonempty name. */
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

            if (depth == 2147483647) {
                throw new IllegalStateException("Maximum tree depth exceeded");
            }
            HierarchicalScheduler created =
                    new HierarchicalScheduler(coordinator, this, name);
            created.nextSibling = firstChild;
            firstChild = created;
            return created;
        }
    }

    /**
     * Reserves [start, end), or returns -1 if it conflicts. Reservations do
     * not wait for availability. If positive IDs are exhausted, a reservation
     * that would otherwise succeed throws IllegalStateException.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must have start < end");
        }

        synchronized (coordinator) {
            // Check this node and its ancestors.
            HierarchicalScheduler current = this;
            while (current != null) {
                if (current.hasOverlap(start, end)) {
                    return -1;
                }
                current = current.parent;
            }

            // Check descendants, without visiting sibling subtrees.
            current = nextInSubtree(this);
            while (current != null) {
                if (current.hasOverlap(start, end)) {
                    return -1;
                }
                current = nextInSubtree(current);
            }

            long id = coordinator.nextId;
            if (id == 0) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            Entry entry = new Entry(id, start, end, reservations);
            coordinator.nextId = (id == 9223372036854775807L) ? 0 : id + 1;
            reservations = entry;
            return id;
        }
    }

    /** Removes a reservation only if it belongs to this node. */
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

    /**
     * Returns a coherent, detached observation of this subtree. Mutating the
     * returned arrays cannot change scheduler state.
     */
    public Snapshot snapshot() {
        synchronized (coordinator) {
            int count = 0;
            HierarchicalScheduler current = this;
            while (current != null) {
                if (count == 2147483647) {
                    throw new IllegalStateException("Subtree is too large");
                }
                count++;
                current = nextInSubtree(current);
            }

            NodeSnapshot[] nodes = new NodeSnapshot[count];
            current = this;
            int index = 0;
            while (current != null) {
                String[] path = new String[current.depth];
                HierarchicalScheduler pathNode = current;
                for (int i = path.length - 1; i >= 0; i--) {
                    path[i] = pathNode.name;
                    pathNode = pathNode.parent;
                }

                int reservationCount = 0;
                Entry entry = current.reservations;
                while (entry != null) {
                    if (reservationCount == 2147483647) {
                        throw new IllegalStateException("Too many reservations");
                    }
                    reservationCount++;
                    entry = entry.next;
                }

                Reservation[] values = new Reservation[reservationCount];
                entry = current.reservations;
                int reservationIndex = 0;
                while (entry != null) {
                    values[reservationIndex++] =
                            new Reservation(entry.id, entry.start, entry.end);
                    entry = entry.next;
                }

                nodes[index++] = new NodeSnapshot(path, values);
                current = nextInSubtree(current);
            }
            return new Snapshot(nodes);
        }
    }

    // Called only with the coordinator monitor held.
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
    // Called only with the coordinator monitor held.
    private HierarchicalScheduler nextInSubtree(HierarchicalScheduler node) {
        if (node.firstChild != null) {
            return node.firstChild;
        }
        while (node != this && node.nextSibling == null) {
            node = node.parent;
        }
        return node == this ? null : node.nextSibling;
    }
}
