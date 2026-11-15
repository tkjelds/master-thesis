import java.util.*;

@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final String name;
    private final Map<String, HierarchicalScheduler> children = new HashMap<>();
    private volatile long nextId = 1;
    private final Map<Long, Reservation> reservations = new HashMap<>();

    public HierarchicalScheduler(String name) {
        this.name = Objects.requireNonNull(name, "Name cannot be null");
    }

    public HierarchicalScheduler child(String name) {
        synchronized (children) {
            return children.computeIfAbsent(name, n -> new HierarchicalScheduler(n));
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) throw new IllegalArgumentException("Invalid interval");
        synchronized (this) {
            for (long id : reservations.keySet()) {
                if (isOverlapping(id, start, end)) return -1;
            }
        }
        long id = nextId++;
        synchronized (this) {
            reservations.put(id, new Reservation(start, end));
        }
        for (HierarchicalScheduler child : children.values()) {
            if (child.reserve(start, end) != -1) return -1;
        }
        return id;
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            return reservations.remove(reservationId) != null;
        }
    }

    public Reservation[] snapshot() {
        List<Reservation> result = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<Long, Reservation> entry : reservations.entrySet()) {
                result.add(entry.getValue());
            }
        }
        synchronized (children) {
            for (HierarchicalScheduler child : children.values()) {
                result.addAll(Arrays.asList(child.snapshot()));
            }
        }
        return result.toArray(new Reservation[0]);
    }

    private boolean isOverlapping(long id, long start, long end) {
        Reservation existing = reservations.get(id);
        return existing != null && !((end <= existing.start) || (start >= existing.end));
    }

    public static class Reservation {
        private final long start;
        private final long end;

        public Reservation(long start, long end) {
            this.start = start;
            this.end = end;
        }

        public long getStart() {
            return start;
        }

        public long getEnd() {
            return end;
        }
    }
}
