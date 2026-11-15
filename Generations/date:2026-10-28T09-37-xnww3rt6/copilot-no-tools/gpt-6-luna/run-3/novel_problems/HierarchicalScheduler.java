@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static final class Coordinator {
        private final Object monitor = new Object();
        private long nextId = 1L;
        private boolean idsExhausted;
    }

    private final Coordinator coordinator;
    private final HierarchicalScheduler parent;
    private final String name;
    private final String path;

    private HierarchicalScheduler[] children = new HierarchicalScheduler[0];
    private int childCount;

    private ReservationData[] reservations = new ReservationData[0];
    private int reservationCount;

    private static final class ReservationData {
        private final long id;
        private final long start;
        private final long end;

        private ReservationData(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Reservation {
        public final long id;
        public final long start;
        public final long end;
        public final String path;

        private Reservation(long id, long start, long end, String path) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.path = path;
        }
    }

    public static final class Snapshot {
        public final String[] paths;
        public final Reservation[] reservations;

        private Snapshot(String[] paths, Reservation[] reservations) {
            this.paths = paths.clone();
            this.reservations = reservations.clone();
        }

        public String[] getPaths() {
            return paths.clone();
        }

        public Reservation[] getReservations() {
            return reservations.clone();
        }
    }

    public HierarchicalScheduler() {
        this.coordinator = new Coordinator();
        this.parent = null;
        this.name = "";
        this.path = "/";
    }

    private HierarchicalScheduler(
            Coordinator coordinator,
            HierarchicalScheduler parent,
            String name,
            String path) {
        this.coordinator = coordinator;
        this.parent = parent;
        this.name = name;
        this.path = path;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (coordinator.monitor) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }

            String childPath = path + encodeSegment(name) + "/";
            HierarchicalScheduler child =
                    new HierarchicalScheduler(coordinator, this, name, childPath);

            if (childCount == children.length) {
                int newLength = children.length == 0 ? 2 : children.length * 2;
                HierarchicalScheduler[] expanded = new HierarchicalScheduler[newLength];
                for (int i = 0; i < childCount; i++) {
                    expanded[i] = children[i];
                }
                children = expanded;
            }

            children[childCount++] = child;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must have start < end");
        }

        synchronized (coordinator.monitor) {
            for (HierarchicalScheduler node = this; node != null; node = node.parent) {
                if (node.hasConflict(start, end)) {
                    return -1L;
                }
            }

            if (coordinator.idsExhausted) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            ensureReservationCapacity();

            long id = coordinator.nextId;
            reservations[reservationCount++] = new ReservationData(id, start, end);

            if (id == Long.MAX_VALUE) {
                coordinator.idsExhausted = true;
            } else {
                coordinator.nextId = id + 1L;
            }

            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (coordinator.monitor) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    for (int j = i + 1; j < reservationCount; j++) {
                        reservations[j - 1] = reservations[j];
                    }
                    reservations[--reservationCount] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (coordinator.monitor) {
            int nodeTotal = countNodes();
            int reservationTotal = countReservations();
            String[] paths = new String[nodeTotal];
            Reservation[] detachedReservations = new Reservation[reservationTotal];

            int[] indexes = new int[] {0, 0};
            fillSnapshot(paths, detachedReservations, indexes);

            return new Snapshot(paths, detachedReservations);
        }
    }

    private boolean hasConflict(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            ReservationData reservation = reservations[i];
            if (start < reservation.end && reservation.start < end) {
                return true;
            }
        }

        for (int i = 0; i < childCount; i++) {
            if (children[i].hasConflict(start, end)) {
                return true;
            }
        }

        return false;
    }

    private void ensureReservationCapacity() {
        if (reservationCount == reservations.length) {
            int newLength = reservations.length == 0 ? 2 : reservations.length * 2;
            ReservationData[] expanded = new ReservationData[newLength];
            for (int i = 0; i < reservationCount; i++) {
                expanded[i] = reservations[i];
            }
            reservations = expanded;
        }
    }

    private int countNodes() {
        int count = 1;
        for (int i = 0; i < childCount; i++) {
            count += children[i].countNodes();
        }
        return count;
    }

    private int countReservations() {
        int count = reservationCount;
        for (int i = 0; i < childCount; i++) {
            count += children[i].countReservations();
        }
        return count;
    }

    private void fillSnapshot(
            String[] paths,
            Reservation[] detachedReservations,
            int[] indexes) {
        paths[indexes[0]++] = path;

        for (int i = 0; i < reservationCount; i++) {
            ReservationData reservation = reservations[i];
            detachedReservations[indexes[1]++] =
                    new Reservation(reservation.id, reservation.start, reservation.end, path);
        }

        for (int i = 0; i < childCount; i++) {
            children[i].fillSnapshot(paths, detachedReservations, indexes);
        }
    }

    private static String encodeSegment(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                encoded.append("%25");
            } else if (c == '/') {
                encoded.append("%2F");
            } else {
                encoded.append(c);
            }
        }
        return encoded.toString();
    }
}
