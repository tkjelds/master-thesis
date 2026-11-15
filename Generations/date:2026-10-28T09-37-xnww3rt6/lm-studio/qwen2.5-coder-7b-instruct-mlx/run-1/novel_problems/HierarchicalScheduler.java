import java.util.HashMap;
import java.util.Map;

@HierarchicalScheduler.ThreadSafe
class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final String name;
    private final HierarchicalScheduler parent;
    private final Map<String, HierarchicalScheduler> children;
    private final Map<Long, Reservation> reservations;

    public HierarchicalScheduler(String name) {
        this.name = name;
        this.parent = null;
        this.children = new HashMap<>();
        this.reservations = new HashMap<>();
    }

    public HierarchicalScheduler child(String name) {
        synchronized (children) {
            return children.computeIfAbsent(name, k -> new HierarchicalScheduler(k, this));
        }
    }

    public long reserve(long start, long end) {
        synchronized (this) {
            if (start >= end) throw new IllegalArgumentException("Invalid interval");
            for (HierarchicalScheduler node = this; node != null; node = node.parent) {
                if (!node.reservations.isEmpty()) {
                    for (Reservation res : node.reservations.values()) {
                        if (!(res.start >= end || start >= res.end)) return -1;
                    }
                }
            }
            long id = System.currentTimeMillis() + reservations.size();
            for (HierarchicalScheduler node = this; node != null; node = node.parent) {
                node.reservations.put(id, new Reservation(start, end));
            }
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            return reservations.remove(reservationId) != null;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            Map<String, String> paths = new HashMap<>();
            for (HierarchicalScheduler node = this; node != null; node = node.parent) {
                paths.put(node.name, paths.getOrDefault("/", "") + "/" + node.name);
            }
            Map<Long, String> rs = new HashMap<>();
            for (Reservation res : reservations.values()) {
                rs.put(res.id, res.toString());
            }
            return new Snapshot(paths, rs);
        }
    }

    private HierarchicalScheduler(String name, HierarchicalScheduler parent) {
        this.name = name;
        this.parent = parent;
        this.children = new HashMap<>();
        this.reservations = new HashMap<>();
    }

    private static class Reservation {
        long start, end;
        long id;

        Reservation(long s, long e) { start = s; end = e; id = System.currentTimeMillis() + idCounter++; }
        @Override public String toString() { return start + "-" + end; }
    }

    private static long idCounter = 0;

    public static class Snapshot {
        final Map<String, String> paths;
        final Map<Long, String> reservations;

        Snapshot(Map<String, String> p, Map<Long, String> r) {
            paths = new HashMap<>(p);
            reservations = new HashMap<>(r);
        }
    }
}
