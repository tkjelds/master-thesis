@HierarchicalScheduler.ThreadSafe
class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final String name;
    private final Map<String, HierarchicalScheduler> children;
    private final List<Reservation> reservations;
    private long nextId;

    HierarchicalScheduler(String name) {
        this.name = validateName(name);
        this.children = new HashMap<>();
        this.reservations = new ArrayList<>();
        this.nextId = 1;
    }

    private String validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        return name;
    }

    HierarchicalScheduler child(String name) {
        synchronized (this) {
            return children.computeIfAbsent(name, k -> new HierarchicalScheduler(k));
        }
    }

    long reserve(long start, long end) {
        synchronized (this) {
            if (start >= end) {
                throw new IllegalArgumentException("Start must be less than end");
            }
            long id = -1;
            for (Reservation reservation : reservations) {
                if (overlaps(reservation, start, end)) {
                    return -1;
                }
            }
            for (HierarchicalScheduler child : children.values()) {
                long result = child.reserve(start, end);
                if (result >= 0) {
                    id = result;
                    break;
                }
            }
            if (id == -1) {
                id = nextId++;
                reservations.add(new Reservation(id, start, end));
            }
            return id;
        }
    }

    boolean cancel(long reservationId) {
        synchronized (this) {
            Iterator<Reservation> iterator = reservations.iterator();
            while (iterator.hasNext()) {
                Reservation reservation = iterator.next();
                if (reservation.id == reservationId) {
                    iterator.remove();
                    return true;
                }
            }
            for (HierarchicalScheduler child : children.values()) {
                if (child.cancel(reservationId)) {
                    return true;
                }
            }
            return false;
        }
    }

    Reservation[] snapshot() {
        synchronized (this) {
            List<Reservation> snapshotList = new ArrayList<>(reservations);
            for (HierarchicalScheduler child : children.values()) {
                snapshotList.addAll(Arrays.asList(child.snapshot()));
            }
            return snapshotList.toArray(new Reservation[0]);
        }
    }

    private static boolean overlaps(Reservation r1, long start, long end) {
        return !(r1.end <= start || r1.start >= end);
    }

    private static class Reservation {
        final long id;
        final long start;
        final long end;

        Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }
}
