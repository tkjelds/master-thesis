@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final long MAX_RESERVATION_ID = Long.MAX_VALUE;
    private volatile Node root;

    public HierarchicalScheduler() {
        this.root = new Node(null, null);
    }

    private static class Node {
        final String name;
        volatile Node parent;
        volatile Node child;
        volatile long[] reservationStarts;
        volatile long[] reservationEnds;
        volatile boolean[] isReserved;

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }

        synchronized void addChild(String childName) {
            if (child == null || !child.name.equals(childName)) {
                Node newChild = new Node(childName, this);
                child = newChild;
            }
        }

        synchronized Node getOrCreateChild(String childName) {
            if (child == null || !child.name.equals(childName)) {
                addChild(childName);
            }
            return child;
        }

        synchronized boolean reserve(long start, long end) {
            if (start >= end || start < 0 || end > MAX_RESERVATION_ID) {
                throw new IllegalArgumentException("Invalid interval");
            }

            // Check for conflicts in this node and all descendants
            if (hasConflict(start, end)) {
                return false;
            }

            // Allocate new reservation ID
            long id = allocateReservationId();
            if (id == -1) {
                return false;
            }

            // Add reservation to this node
            if (reservationStarts == null) {
                reservationStarts = new long[1];
                reservationEnds = new long[1];
                isReserved = new boolean[1];
            } else {
                // Double the array if full
                if (isReserved.length == reservationStarts.length) {
                    long[] newStarts = new long[reservationStarts.length * 2];
                    long[] newEnds = new long[reservationEnds.length * 2];
                    boolean[] newIsReserved = new boolean[isReserved.length * 2];

                    System.arraycopy(reservationStarts, 0, newStarts, 0, reservationStarts.length);
                    System.arraycopy(reservationEnds, 0, newEnds, 0, reservationEnds.length);
                    System.arraycopy(isReserved, 0, newIsReserved, 0, isReserved.length);

                    reservationStarts = newStarts;
                    reservationEnds = newEnds;
                    isReserved = newIsReserved;
                }
            }

            reservationStarts[isReserved.length - 1] = start;
            reservationEnds[isReserved.length - 1] = end;
            isReserved[isReserved.length - 1] = true;

            return true;
        }

        private boolean hasConflict(long start, long end) {
            if (reservationStarts != null) {
                for (int i = 0; i < isReserved.length; i++) {
                    if (isReserved[i]) {
                        long s = reservationStarts[i];
                        long e = reservationEnds[i];

                        if (!(end <= s || start >= e)) {
                            return true;
                        }
                    }
                }
            }

            if (child != null) {
                return child.hasConflict(start, end);
            }
            return false;
        }

        private long allocateReservationId() {
            synchronized (this) {
                for (long i = 0; i <= MAX_RESERVATION_ID; i++) {
                    boolean exists = false;
                    if (reservationStarts != null) {
                        for (int j = 0; j < isReserved.length; j++) {
                            if (isReserved[j] && reservationStarts[j] == i) {
                                exists = true;
                                break;
                            }
                        }
                    }

                    if (!exists) {
                        return i;
                    }
                }
            }
            return -1;
        }

        synchronized boolean cancel(long reservationId) {
            if (reservationStarts == null || reservationEnds == null || isReserved == null) {
                return false;
            }

            for (int i = 0; i < isReserved.length; i++) {
                if (isReserved[i] && reservationStarts[i] == reservationId) {
                    isReserved[i] = false;
                    return true;
                }
            }

            if (child != null) {
                return child.cancel(reservationId);
            }
            return false;
        }

        synchronized NodeSnapshot snapshot() {
            // Collect all reservations in this subtree
            long[][] starts = new long[1024];
            long[][] ends = new long[1024];
            int count = 0;

            collectReservations(this, starts, ends, count);

            // Collect all paths
            String[] paths = new String[1024];
            collectPaths(this, paths);

            return new NodeSnapshot(paths, starts, ends);
        }

        private void collectReservations(Node node, long[][] starts, long[][] ends, int count) {
            if (node.reservationStarts != null && node.isReserved != null) {
                for (int i = 0; i < node.isReserved.length; i++) {
                    if (node.isReserved[i]) {
                        starts[count] = new long[]{node.reservationStarts[i]};
                        ends[count] = new long[]{node.reservationEnds[i]};
                        count++;
                    }
                }
            }

            if (node.child != null) {
                collectReservations(node.child, starts, ends, count);
            }
        }

        private void collectPaths(Node node, String[] paths) {
            if (node.name != null && !node.name.isEmpty()) {
                String path = node.name;
                if (node.parent != null) {
                    path = node.parent.name + "/" + path;
                }
                paths[0] = path;

                if (node.child != null) {
                    collectPaths(node.child, paths);
                }
            }
        }

        private static class NodeSnapshot {
            final String[] paths;
            final long[][] reservationStarts;
            final long[][] reservationEnds;

            NodeSnapshot(String[] paths, long[][] starts, long[][] ends) {
                this.paths = paths;
                this.reservationStarts = starts;
                this.reservationEnds = ends;
            }
        }

        private static class DetachedReservation {
            final long id;
            final long start;
            final long end;

            DetachedReservation(long id, long start, long end) {
                this.id = id;
                this.start = start;
                this.end = end;
            }
        }

        private static class DetachedSnapshot {
            final String[] paths;
            final DetachedReservation[] reservations;

            DetachedSnapshot(String[] paths, DetachedReservation[] reservations) {
                this.paths = paths;
                this.reservations = reservations;
            }
        }

        private static class PathReservation {
            final String path;
            final long start;
            final long end;

            PathReservation(String path, long start, long end) {
                this.path = path;
                this.start = start;
                this.end = end;
            }
        }

        private static class PathReservationSnapshot {
            final PathReservation[] reservations;

            PathReservationSnapshot(PathReservation[] reservations) {
                this.reservations = reservations;
            }
        }

        private static class PathReservationSnapshotBuilder {
            final String[] paths;
            final long[][] starts;
            final long[][] ends;

            PathReservationSnapshotBuilder(String[] paths, long[][] starts, long[][] ends) {
                this.paths = paths;
                this.starts = starts;
                this.ends = ends;
            }

            PathReservationSnapshot build() {
                int count = 0;
                for (int i = 0; I < paths.length; i++) {
                    if (starts[i] != null && ends[i] != null) {
                        count++;
                    }
                }

                PathReservation[] reservations = new PathReservation[count];
                int index = 0;

                for (int i = 0; i < paths.length; i++) {
                    if (starts[i] != null && ends[i] != null) {
                        reservations[index++] = new PathReservation(paths[i], starts[i][0], ends[i][0]);
                    }
                }

                return new PathReservationSnapshot(reservations);
            }
        }
    }

    public Node getOrCreateChild(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Invalid name");
        }

        synchronized (root) {
            return root.getOrCreateChild(name);
        }
    }

    public long reserve(long start, long end) {
        if (start >= end || start < 0 || end > MAX_RESERVATION_ID) {
            throw new IllegalArgumentException("Invalid interval");
        }

        Node node = root;
        long id = -1;

        synchronized (node) {
            if (!node.reserve(start, end)) {
                return -1;
            }
        }

        // Check all ancestors
        Node current = node.parent;
        while (current != null) {
            synchronized (current) {
                if (!current.reserve(start, end)) {
                    // Rollback the reservation
                    synchronized (node) {
                        node.cancel(id);
                    }
                    return -1;
                }
            }
            current = current.parent;
        }

        // Check all descendants
        if (!checkDescendants(node, start, end)) {
            // Rollback the reservation
            synchronized (node) {
                node.cancel(id);
            }
            return -1;
        }

        // Allocate unique ID
        id = allocateUniqueId(node);

        return id;
    }

    private boolean checkDescendants(Node node, long start, long end) {
        if (node.child != null) {
            synchronized (node.child) {
                if (!node.child.reserve(start, end)) {
                    return false;
                }
            }

            // Check descendants recursively
            if (!checkDescendants(node.child, start, end)) {
                return false;
            }
        }

        return true;
    }

    private long allocateUniqueId(Node node) {
        synchronized (node) {
            for (long i = 0; i <= MAX_RESERVATION_ID; i++) {
                boolean exists = false;
                if (node.reservationStarts != null) {
                    for (int j = 0; j < node.isReserved.length; j++) {
                        if (node.isReserved[j] && node.reservationStarts[j] == i) {
                            exists = true;
                            break;
                        }
                    }
                }

                if (!exists) {
                    return i;
                }
            }
        }
        return -1;
    }

    public boolean cancel(long reservationId) {
        if (reservationId < 0 || reservationId > MAX_RESERVATION_ID) {
            throw new IllegalArgumentException("Invalid reservation ID");
        }

        Node node = root;
        boolean canceled = false;

        synchronized (node) {
            if (node.cancel(reservationId)) {
                canceled = true;
            }
        }

        // Check all ancestors
        Node current = node.parent;
        while (current != null && !canceled) {
            synchronized (current) {
                canceled = current.cancel(reservationId);
            }
            if (!canceled) {
                current = current.parent;
            } else {
                break;
            }
        }

        return canceled;
    }

    public NodeSnapshot snapshot() {
        synchronized (root) {
            return root.snapshot();
        }
    }

    public static class DetachedReservation {
        final long id;
        final long start;
        final long end;

        public DetachedReservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static class DetachedSnapshot {
        final String[] paths;
        final DetachedReservation[] reservations;

        public DetachedSnapshot(String[] paths, DetachedReservation[] reservations) {
            this.paths = paths;
            this.reservations = reservations;
        }
    }

    public static class NodeSnapshot {
        final String[] paths;
        final long[][] reservationStarts;
        final long[][] reservationEnds;

        public NodeSnapshot(String[] paths, long[][] starts, long[][] ends) {
            this.paths = paths;
            this.reservationStarts = starts;
            this.reservationEnds = ends;
        }
    }

    public static class PathReservationSnapshot {
        final String[] paths;
        final long[][] reservationStarts;
        final long[][] reservationEnds;

        public PathReservationSnapshot(String[] paths, long[][] starts, long[][] ends) {
            this.paths = paths;
            this.reservationStarts = starts;
            this.reservationEnds = ends;
        }
    }

    private static class PathReservation {
        final String path;
        final long start;
        final long end;

        public PathReservation(String path, long start, long end) {
            this.path = path;
            this.start = start;
            this.end = end;
        }
    }

    private static class PathReservationSnapshotBuilder {
        final String[] paths;
        final long[][] starts;
        final long[][] ends;

        public PathReservationSnapshotBuilder(String[] paths, long[][] starts, long[][] ends) {
            this.paths = paths;
            this.starts = starts;
            this.ends = ends;
        }

        public PathReservationSnapshot build() {
            int count = 0;
            for (int i = 0; i < paths.length; i++) {
                if (starts[i] != null && ends[i] != null) {
                    count++;
                }
            }

            PathReservation[] reservations = new PathReservation[count];
            int index = 0;

            for (int i = 0; i < paths.length; i++) {
                if (starts[i] != null && ends[i] != null) {
                    reservations[index++] = new PathReservation(paths[i], starts[i][0], ends[i][0]);
                }
            }

            return new PathReservationSnapshot(reservations);
        }
    }
}
