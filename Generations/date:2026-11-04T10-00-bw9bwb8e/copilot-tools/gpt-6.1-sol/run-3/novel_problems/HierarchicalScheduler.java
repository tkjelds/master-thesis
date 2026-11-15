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
        public final String nodePath;

        private Reservation(long id, long start, long end, String nodePath) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.nodePath = nodePath;
        }
    }

    public static final class Snapshot {
        public final String[] nodePaths;
        public final Reservation[] reservations;

        private Snapshot(String[] nodePaths, Reservation[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    // All mutable tree state is guarded by the shared coordinator.
    private HierarchicalScheduler firstChild;
    private HierarchicalScheduler lastChild;
    private HierarchicalScheduler nextSibling;
    private Entry reservations;

    public HierarchicalScheduler() {
        coordinator = new Coordinator();
        parent = null;
        name = "";
        path = "/";
    }

    private HierarchicalScheduler(HierarchicalScheduler parent, String name) {
        this.coordinator = parent.coordinator;
        this.parent = parent;
        this.name = name;
        String segment = escapeName(name);
        this.path = parent.parent == null
                ? "/" + segment
                : parent.path + "/" + segment;
    }

    /**
     * Returns the unique child with this name. Only null and empty names
     * are invalid; slashes and backslashes are escaped in snapshot paths.
     */
    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler child = firstChild;
                    child != null; child = child.nextSibling) {
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
     * Returns a positive reservation ID, or -1 on conflict. Does not wait
     * for availability. Throws IllegalStateException if IDs are exhausted.
     */
    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Interval must satisfy start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler ancestor = parent;
                    ancestor != null; ancestor = ancestor.parent) {
                if (ancestor.hasOverlap(start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler node = this;
                    node != null; node = nextInSubtree(node)) {
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
            coordinator.nextId = id == 9223372036854775807L ? 0 : id + 1;
            return id;
        }
    }

    /**
     * Removes only a reservation owned by this node.
     */
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
     * Returns a coherent, detached snapshot including this node and all
     * descendants. The root path is "/". Each segment escapes '\' as '\\'
     * and '/' as '\/'. Empty nodes are included in nodePaths.
     *
     * Returned arrays belong exclusively to the snapshot; modifying them
     * cannot affect the scheduler or another snapshot.
     */
    public Snapshot snapshot() {
        synchronized (coordinator) {
            int nodeCount = 0;
            int reservationCount = 0;

            for (HierarchicalScheduler node = this;
                    node != null; node = nextInSubtree(node)) {
                if (nodeCount == 2147483647) {
                    throw new IllegalStateException("Too many nodes for a snapshot");
                }
                nodeCount++;

                for (Entry entry = node.reservations;
                        entry != null; entry = entry.next) {
                    if (reservationCount == 2147483647) {
                        throw new IllegalStateException(
                                "Too many reservations for a snapshot");
                    }
                    reservationCount++;
                }
            }

            String[] paths = new String[nodeCount];
            Reservation[] copies = new Reservation[reservationCount];
            int nodeIndex = 0;
            int reservationIndex = 0;

            for (HierarchicalScheduler node = this;
                    node != null; node = nextInSubtree(node)) {
                paths[nodeIndex++] = node.path;

                for (Entry entry = node.reservations;
                        entry != null; entry = entry.next) {
                    copies[reservationIndex++] = new Reservation(
                            entry.id, entry.start, entry.end, node.path);
                }
            }

            return new Snapshot(paths, copies);
        }
    }

    private boolean hasOverlap(long start, long end) {
        for (Entry entry = reservations; entry != null; entry = entry.next) {
            if (start < entry.end && entry.start < end) {
                return true;
            }
        }
        return false;
    }

    // Iterative traversal avoids recursion limits for deeply nested trees.
    // Called only while holding the coordinator monitor.
    private HierarchicalScheduler nextInSubtree(HierarchicalScheduler node) {
        if (node.firstChild != null) {
            return node.firstChild;
        }

        while (node != this) {
            if (node.nextSibling != null) {
                return node.nextSibling;
            }
            node = node.parent;
        }

        return null;
    }

    private static String escapeName(String name) {
        int extra = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\') {
                extra++;
            }
        }

        if (extra == 0) {
            return name;
        }
        if (name.length() > 2147483647 - extra) {
            throw new IllegalStateException("Escaped name is too large");
        }

        char[] escaped = new char[name.length() + extra];
        int index = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\') {
                escaped[index++] = '\\';
            }
            escaped[index++] = c;
        }
        return new String(escaped);
    }
}
