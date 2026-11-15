@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final String name;
    private final long startTime;
    private final long endTime;
    private long reservationId = 0;
    private HierarchicalScheduler parent = null;
    private HierarchicalScheduler[] children = new HierarchicalScheduler[0];
    private Reservation[] reservations = new Reservation[0];

    public HierarchicalScheduler(String name) {
        this.name = name;
        this.startTime = 0;
        this.endTime = Long.MAX_VALUE;
    }

    public HierarchicalScheduler child(String name) {
        for (HierarchicalScheduler child : children) {
            if (child.name.equals(name)) {
                return child;
            }
        }
        HierarchicalScheduler newChild = new HierarchicalScheduler(name);
        newChild.parent = this;
        synchronized (this) {
            children = Arrays.copyOf(children, children.length + 1);
            children[children.length - 1] = newChild;
        }
        return newChild;
    }

    public long reserve(long start, long end) {
        if (start >= end || start < startTime || end > endTime) {
            throw new IllegalArgumentException("Invalid interval");
        }
        synchronized (this) {
            for (Reservation reservation : reservations) {
                if (reservation.start < end && start < reservation.end) {
                    return -1;
                }
            }
            for (HierarchicalScheduler child : children) {
                if (child.reserve(start, end) != -1) {
                    return -1;
                }
            }
            reservationId++;
            reservations = Arrays.copyOf(reservations, reservations.length + 1);
            reservations[reservations.length - 1] = new Reservation(reservationId, start, end);
            return reservationId;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i].id == reservationId) {
                    System.arraycopy(reservations, i + 1, reservations, i, reservations.length - i - 1);
                    reservations = Arrays.copyOf(reservations, reservations.length - 1);
                    return true;
                }
            }
            for (HierarchicalScheduler child : children) {
                if (child.cancel(reservationId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public NodeAndReservations snapshot() {
        synchronized (this) {
            return new NodeAndReservations(name, startTime, endTime, reservationId, copy(reservations));
        }
    }

    private HierarchicalScheduler[] copy(HierarchicalScheduler[] array) {
        return Arrays.copyOf(array, array.length);
    }

    private Reservation[] copy(Reservation[] array) {
        return Arrays.copyOf(array, array.length);
    }

    public static class NodeAndReservations {
        private final String name;
        private final long startTime;
        private final long endTime;
        private final long reservationId;
        private final Reservation[] reservations;

        public NodeAndReservations(String name, long startTime, long endTime, long reservationId, Reservation[] reservations) {
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.reservationId = reservationId;
            this.reservations = reservations;
        }

        // Getters for the fields
    }

    public static class Reservation {
        private final long id;
        private final long start;
        private final long end;

        public Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        // Getters for the fields
    }
}
