@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static long reservationIdCounter = 0;
    private final String name;
    private final HierarchicalScheduler parent;
    private volatile HashMap<String, HierarchicalScheduler> children;
    private final Object lock = new Object();
    private volatile long[] reservations;

    public HierarchicalScheduler(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Invalid name");
        this.name = name;
        this.parent = null;
        this.children = new HashMap<>();
        this.reservations = new long[0];
    }

    public HierarchicalScheduler child(String name) {
        synchronized (this.lock) {
            return children.computeIfAbsent(name, n -> new HierarchicalScheduler(n, this));
        }
    }

    private HierarchicalScheduler(String name, HierarchicalScheduler parent) {
        this.name = name;
        this.parent = parent;
        this.children = new HashMap<>();
        this.reservations = new long[0];
    }

    private boolean isConflict(long start, long end) {
        for (long[] reservation : reservations) {
            if (!(start >= reservation[1] || end <= reservation[0])) {
                return true;
            }
        }
        for (HierarchicalScheduler child : children.values()) {
            if (child.isConflict(start, end)) {
                return true;
            }
        }
        for (HierarchicalScheduler ancestor : getAncestors()) {
            for (long[] reservation : ancestor.reservations) {
                if (!(start >= reservation[1] || end <= reservation[0])) {
                    return true;
                }
            }
        }
        for (HierarchicalScheduler descendant : getDescendants()) {
            if (descendant.isConflict(start, end)) {
                return true;
            }
        }
        return false;
    }

    private List<HierarchicalScheduler> getAncestors() {
        List<HierarchicalScheduler> ancestors = new ArrayList<>();
        HierarchicalScheduler current = this;
        while (current.parent != null) {
            ancestors.add(current.parent);
            current = current.parent;
        }
        return ancestors;
    }

    private Set<HierarchicalScheduler> getDescendants() {
        HashSet<HierarchicalScheduler> descendants = new HashSet<>();
        for (HierarchicalScheduler child : children.values()) {
            descendants.add(child);
            descendants.addAll(child.getDescendants());
        }
        return descendants;
    }

    public int reserve(long start, long end) {
        if (start >= end) throw new IllegalArgumentException("Invalid interval");
        synchronized (this.lock) {
            if (isConflict(start, end)) return -1;
            long[] newReservations = Arrays.copyOf(reservations, reservations.length + 2);
            newReservations[newReservations.length - 2] = start;
            newReservations[newReservations.length - 1] = end;
            this.reservations = newReservations;
            return reservationIdCounter++;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this.lock) {
            for (int i = 0; i < reservations.length; i += 2) {
                if (reservations[i] == reservationId) {
                    long[] newReservations = Arrays.copyOf(reservations, reservations.length - 2);
                    System.arraycopy(reservations, i + 2, newReservations, i, reservations.length - i - 2);
                    this.reservations = newReservations;
                    return true;
                }
            }
            for (HierarchicalScheduler child : children.values()) {
                if (child.cancel(reservationId)) return true;
            }
            return false;
        }
    }

    public DetachedReservation snapshot() {
        synchronized (this.lock) {
            String path = getPath();
            long[][] snapshotReservations = Arrays.copyOf(reservations, reservations.length);
            for (HierarchicalScheduler child : children.values()) {
                SnapshotData snapshotData = child.snapshot();
                path += "/" + snapshotData.getPath();
                for (long[] reservation : snapshotData.getReservations()) {
                    snapshotReservations = Arrays.copyOf(snapshotReservations, snapshotReservations.length + 2);
                    System.arraycopy(reservation, 0, snapshotReservations, snapshotReservations.length - 2, reservation.length);
                }
            }
            return new DetachedReservation(path, snapshotReservations);
        }
    }

    private String getPath() {
        if (parent == null) return name;
        return parent.getPath() + "/" + name;
    }

    public static class DetachedReservation implements AutoCloseable {
        private final String path;
        private final long[][] reservations;

        public DetachedReservation(String path, long[][] reservations) {
            this.path = path;
            this.reservations = reservations;
        }

        public String getPath() {
            return path;
        }

        public long[][] getReservations() {
            return reservations;
        }

        @Override
        public void close() {
            // No-op, DetachedReservation is intended to be used for snapshot purposes only
        }
    }
}
