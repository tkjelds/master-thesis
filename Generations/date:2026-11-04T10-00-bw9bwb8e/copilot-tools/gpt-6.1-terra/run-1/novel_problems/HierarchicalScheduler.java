@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        long lastReservationId;
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
        // Absolute path as child-name components; the root has an empty path.
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
    private final int indexInParent;

    private HierarchicalScheduler[] children = new HierarchicalScheduler[0];
    private int childCount;

    private Reservation[] reservations = new Reservation[0];
    private int reservationCount;

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = null;
        this.indexInParent = -1;
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
            HierarchicalScheduler parent,
            String name,
            int indexInParent) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.indexInParent = indexInParent;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }

            if (childCount == children.length) {
                HierarchicalScheduler[] expanded =
                        new HierarchicalScheduler[expandedCapacity(children.length)];
                for (int i = 0; i < childCount; i++) {
                    expanded[i] = children[i];
                }
                children = expanded;
            }

            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, childCount);
            children[childCount] = child;
            childCount++;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must have start < end");
        }

        synchronized (coordinator) {
            for (HierarchicalScheduler node = parent; node != null; node = node.parent) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler node = this;
                    node != null;
                    node = nextInSubtree(node, this)) {
                if (node.hasOverlap(start, end)) {
                    return -1;
                }
            }

            // IDs are never reused; exhaustion is reported rather than wrapping.
            if (coordinator.lastReservationId == 9223372036854775807L) {
                throw new IllegalStateException("Reservation IDs exhausted");
            }

            if (reservationCount == reservations.length) {
                Reservation[] expanded =
                        new Reservation[expandedCapacity(reservations.length)];
                for (int i = 0; i < reservationCount; i++) {
                    expanded[i] = reservations[i];
                }
                reservations = expanded;
            }

            long id = coordinator.lastReservationId + 1;
            Reservation reservation = new Reservation(id, start, end);
            reservations[reservationCount] = reservation;
            reservationCount++;
            coordinator.lastReservationId = id;
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    for (int j = i + 1; j < reservationCount; j++) {
                        reservations[j - 1] = reservations[j];
                    }
                    reservationCount--;
                    reservations[reservationCount] = null;
                    return true;
                }
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
                int depth = 0;
                for (HierarchicalScheduler ancestor = node;
                        ancestor.parent != null;
                        ancestor = ancestor.parent) {
                    if (depth == 2147483647) {
                        throw new IllegalStateException("Path is too deep to snapshot");
                    }
                    depth++;
                }

                String[] path = new String[depth];
                HierarchicalScheduler ancestor = node;
                for (int i = depth - 1; i >= 0; i--) {
                    path[i] = ancestor.name;
                    ancestor = ancestor.parent;
                }

                Reservation[] copies = new Reservation[node.reservationCount];
                for (int i = 0; i < node.reservationCount; i++) {
                    Reservation reservation = node.reservations[i];
                    copies[i] = new Reservation(
                            reservation.id, reservation.start, reservation.end);
                }
                nodes[index] = new NodeSnapshot(path, copies);
                index++;
            }
            return new Snapshot(nodes);
        }
    }

    private boolean hasOverlap(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            Reservation reservation = reservations[i];
            if (start < reservation.end && reservation.start < end) {
                return true;
            }
        }
        return false;
    }

    private static HierarchicalScheduler nextInSubtree(
            HierarchicalScheduler node, HierarchicalScheduler subtreeRoot) {
        if (node.childCount > 0) {
            return node.children[0];
        }

        while (node != subtreeRoot) {
            HierarchicalScheduler parent = node.parent;
            int nextIndex = node.indexInParent + 1;
            if (nextIndex < parent.childCount) {
                return parent.children[nextIndex];
            }
            node = parent;
        }
        return null;
    }

    private static int expandedCapacity(int currentCapacity) {
        if (currentCapacity == 2147483647) {
            throw new IllegalStateException("Structure is too large");
        }
        if (currentCapacity == 0) {
            return 4;
        }
        if (currentCapacity > 2147483647 / 2) {
            return 2147483647;
        }
        return currentCapacity * 2;
    }
}
