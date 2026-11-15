import java.util.*;

@HierarchicalScheduler.ThreadSafe
class HierarchicalScheduler {
    @interface ThreadSafe {}

    private static long nextReservationId = 1;
    private final String name;
    private HierarchicalScheduler parent;
    private Map<String, HierarchicalScheduler> children = new HashMap<>();
    private List<Reservation> reservations = new ArrayList<>();

    public HierarchicalScheduler(String name) {
        this.name = Objects.requireNonNull(name);
        this.parent = null;
    }

    public HierarchicalScheduler child(String name) {
        synchronized (children) {
            return children.computeIfAbsent(name, n -> new HierarchicalScheduler(n, this));
        }
    }

    private HierarchicalScheduler(String name, HierarchicalScheduler parent) {
        this.name = Objects.requireNonNull(name);
        this.parent = parent;
    }

    public long reserve(long start, long end) {
        synchronized (this) {
            if (start >= end) {
                throw new IllegalArgumentException("Invalid interval");
            }
            for (Reservation r : reservations) {
                if (r.start < end && start < r.end) {
                    return -1;
                }
            }
            for (HierarchicalScheduler child : children.values()) {
                if (child.reserve(start, end) != -1) {
                    return -1;
                }
            }
            return nextReservationId++;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            Iterator<Reservation> it = reservations.iterator();
            while (it.hasNext()) {
                Reservation r = it.next();
                if (r.id == reservationId) {
                    it.remove();
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

    public Snapshot snapshot() {
        synchronized (this) {
            List<String> paths = new ArrayList<>();
            Stack<HierarchicalScheduler> stack = new Stack<>();
            HierarchicalScheduler current = this;
            while (current != null) {
                stack.push(current);
                current = current.parent;
            }
            while (!stack.isEmpty()) {
                HierarchicalScheduler node = stack.pop();
                paths.add(node.name);
            }
            List<Reservation> resList = new ArrayList<>(reservations);
            for (HierarchicalScheduler child : children.values()) {
                resList.addAll(child.snapshot().reservations);
            }
            return new Snapshot(paths, resList);
        }
    }

    private static class Reservation {
        long id;
        long start;
        long end;

        Reservation(long start, long end) {
            this.id = nextReservationId++;
            this.start = start;
            this.end = end;
        }
    }

    public static class Snapshot {
        List<String> paths;
        List<Reservation> reservations;

        Snapshot(List<String> paths, List<Reservation> reservations) {
            this.paths = new ArrayList<>(paths);
            this.reservations = new ArrayList<>(reservations);
        }
    }
}
