@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}


    private static final class Shared {
        final Object lock = new Object();
        long nextId = 1L;
    }

    private static final class Reservation {
        final long id;
        final long start;
        final long end;

        Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class ReservationInfo {
        private final String ownerPath;
        private final long id;
        private final long start;
        private final long end;

        ReservationInfo(String ownerPath, long id, long start, long end) {
            this.ownerPath = ownerPath;
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public String getOwnerPath() {
            return ownerPath;
        }

        public long getId() {
            return id;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }

    public static final class Snapshot {
        private final String[] nodePaths;
        private final ReservationInfo[] reservations;

        Snapshot(String[] nodePaths, ReservationInfo[] reservations) {
            this.nodePaths = nodePaths;
            this.reservations = reservations;
        }

        public String[] getNodePaths() {
            String[] copy = new String[nodePaths.length];
            for (int i = 0; i < nodePaths.length; i++) {
                copy[i] = nodePaths[i];
            }
            return copy;
        }

        public ReservationInfo[] getReservations() {
            ReservationInfo[] copy = new ReservationInfo[reservations.length];
            for (int i = 0; i < reservations.length; i++) {
                copy[i] = reservations[i];
            }
            return copy;
        }
    }

    private final Shared shared;
    private final HierarchicalScheduler parent;
    private final String name;
    private HierarchicalScheduler[] children = new HierarchicalScheduler[4];
    private int childCount;
    private Reservation[] reservations = new Reservation[4];
    private int reservationCount;

    public HierarchicalScheduler() {
        this.shared = new Shared();
        this.parent = null;
        this.name = "";
    }

    private HierarchicalScheduler(Shared shared, HierarchicalScheduler parent, String name) {
        this.shared = shared;
        this.parent = parent;
        this.name = name;
    }

    public HierarchicalScheduler child(String childName) {
        if (childName == null || childName.isEmpty() || childName.indexOf('/') >= 0) {
            throw new IllegalArgumentException("invalid child name");
        }
        synchronized (shared.lock) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(childName)) {
                    return children[i];
                }
            }
            HierarchicalScheduler created = new HierarchicalScheduler(shared, this, childName);
            if (childCount == children.length) {
                HierarchicalScheduler[] grown = new HierarchicalScheduler[children.length * 2];
                for (int i = 0; i < childCount; i++) {
                    grown[i] = children[i];
                }
                children = grown;
            }
            children[childCount++] = created;
            return created;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("invalid interval");
        }
        synchronized (shared.lock) {
            for (HierarchicalScheduler n = this; n != null; n = n.parent) {
                if (n.overlapsOwn(start, end)) {
                    return -1L;
                }
            }
            if (this.subtreeBelowConflicts(start, end)) {
                return -1L;
            }
            long id = shared.nextId++;
            if (reservationCount == reservations.length) {
                Reservation[] grown = new Reservation[reservations.length * 2];
                for (int i = 0; i < reservationCount; i++) {
                    grown[i] = reservations[i];
                }
                reservations = grown;
            }
            reservations[reservationCount++] = new Reservation(id, start, end);
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (shared.lock) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    for (int j = i; j < reservationCount - 1; j++) {
                        reservations[j] = reservations[j + 1];
                    }
                    reservations[--reservationCount] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (shared.lock) {
            int nodeCount = countNodes();
            int resTotal = countReservations();
            String[] paths = new String[nodeCount];
            ReservationInfo[] infos = new ReservationInfo[resTotal];
            int[] nodePos = new int[1];
            int[] resPos = new int[1];
            fill("", paths, nodePos, infos, resPos);
            return new Snapshot(paths, infos);
        }
    }

    // ---- helpers; all called while holding shared.lock ----

    private boolean overlapsOwn(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            Reservation r = reservations[i];
            if (r.start < end && start < r.end) {
                return true;
            }
        }
        return false;
    }

    private boolean subtreeBelowConflicts(long start, long end) {
        for (int i = 0; i < childCount; i++) {
            HierarchicalScheduler c = children[i];
            if (c.overlapsOwn(start, end) || c.subtreeBelowConflicts(start, end)) {
                return true;
            }
        }
        return false;
    }

    private int countNodes() {
        int total = 1;
        for (int i = 0; i < childCount; i++) {
            total += children[i].countNodes();
        }
        return total;
    }

    private int countReservations() {
        int total = reservationCount;
        for (int i = 0; i < childCount; i++) {
            total += children[i].countReservations();
        }
        return total;
    }

    private void fill(String path, String[] paths, int[] nodePos,
                      ReservationInfo[] infos, int[] resPos) {
        paths[nodePos[0]++] = path;
        for (int i = 0; i < reservationCount; i++) {
            Reservation r = reservations[i];
            infos[resPos[0]++] = new ReservationInfo(path, r.id, r.start, r.end);
        }
        for (int i = 0; i < childCount; i++) {
            HierarchicalScheduler c = children[i];
            String childPath = path.isEmpty() ? c.name : path + "/" + c.name;
            c.fill(childPath, paths, nodePos, infos, resPos);
        }
    }
}
