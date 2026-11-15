import java.util.*;

@HierarchicalScheduler.ThreadSafe
class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static int reservationId = 0;
    private final String name;
    private final Map<String, HierarchicalScheduler> children = new HashMap<>();
    private final List<DetachedReservation> reservations = new ArrayList<>();

    public HierarchicalScheduler(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name cannot be null or empty");
        }
        this.name = name;
    }

    public HierarchicalScheduler child(String name) {
        synchronized (this) {
            return children.computeIfAbsent(name, n -> new HierarchicalScheduler(n));
        }
    }

    public int reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Invalid interval: start must be less than end");
        }
        return reserveHelper(this, start, end);
    }

    private int reserveHelper(HierarchicalScheduler currentNode, long start, long end) {
        synchronized (currentNode) {
            for (DetachedReservation reservation : currentNode.reservations) {
                if (reservationsOverlap(reservation, start, end)) {
                    return -1;
                }
            }

            for (HierarchicalScheduler child : currentNode.children.values()) {
                int id = reserveHelper(child, start, end);
                if (id != -1) {
                    return id;
                }
            }

            int newId = reservationId++;
            currentNode.reservations.add(new DetachedReservation(start, end, newId));
            return newId;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            if (removeReservations(this, reservationId)) {
                return true;
            }
            for (HierarchicalScheduler child : children.values()) {
                if (removeReservations(child, reservationId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean removeReservations(HierarchicalScheduler currentNode, long reservationId) {
        synchronized (currentNode) {
            Iterator<DetachedReservation> it = currentNode.reservations.iterator();
            while (it.hasNext()) {
                DetachedReservation reservation = it.next();
                if (reservation.id == reservationId) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        List<NodePath> paths = new ArrayList<>();
        Map<Long, ReservationInfo> reservationsMap = new HashMap<>();

        collectPaths(this, paths);
        collectReservations(this, reservationsMap);

        return new Snapshot(paths.toArray(new NodePath[0]), reservationsMap.values().toArray(new ReservationInfo[0]));
    }

    private void collectPaths(HierarchicalScheduler currentNode, List<NodePath> paths) {
        for (Map.Entry<String, HierarchicalScheduler> entry : currentNode.children.entrySet()) {
            collectPaths(entry.getValue(), paths);
        }
        paths.add(new NodePath(currentNode.getPath()));
    }

    private void collectReservations(HierarchicalScheduler currentNode, Map<Long, ReservationInfo> reservationsMap) {
        for (DetachedReservation reservation : currentNode.reservations) {
            reservationsMap.put(reservation.id, new ReservationInfo(currentNode.getPath(), reservation.start, reservation.end));
        }
        for (HierarchicalScheduler child : currentNode.children.values()) {
            collectReservations(child, reservationsMap);
        }
    }

    private List<String> getPath() {
        HierarchicalScheduler current = this;
        List<String> path = new ArrayList<>();
        while (current != null) {
            path.add(current.name);
            current = current.parent;
        }
        Collections.reverse(path);
        return new ArrayList<>(path.subList(1, path.size()));
    }

    private static boolean reservationsOverlap(DetachedReservation reservation, long start, long end) {
        return Math.max(reservation.start, start) < Math.min(reservation.end, end);
    }

    public static class Snapshot {
        private final NodePath[] nodePaths;
        private final ReservationInfo[] reservations;

        public Snapshot(NodePath[] nodePaths, ReservationInfo[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }

        public NodePath[] getNodePaths() {
            return nodePaths;
        }

        public ReservationInfo[] getReservations() {
            return reservations;
        }
    }

    public static class NodePath {
        private final List<String> path;

        public NodePath(List<String> path) {
            this.path = new ArrayList<>(path);
        }

        public List<String> getPath() {
            return path;
        }
    }

    public static class ReservationInfo {
        private final List<String> path;
        private final long start;
        private final long end;

        public ReservationInfo(List<String> path, long start, long end) {
            this.path = new ArrayList<>(path);
            this.start = start;
            this.end = end;
        }

        public List<String> getPath() {
            return path;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    private static class DetachedReservation {
        final long start;
        final long end;
        final int id;

        public DetachedReservation(long start, long end, int id) {
            this.start = start;
            this.end = end;
            this.id = id;
        }
    }

    private HierarchicalScheduler parent = null;

    protected void addParent(HierarchicalScheduler parent) {
        this.parent = parent;
    }
}
