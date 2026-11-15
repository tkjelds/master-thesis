```java
public class HierarchicalScheduler {
    private static long nextId = 1;
    private final String name;
    private final HierarchicalScheduler parent;
    private final ReservationMap reservations;
    private final ReservationNode root;

    public HierarchicalScheduler(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Invalid name");
        this.name = name;
        this.parent = null;
        this.reservations = new ReservationMap();
        this.root = new ReservationNode(name, null);
    }

    public HierarchicalScheduler child(String name) {
        synchronized (root) {
            ReservationNode node = reservations.get(name);
            if (node == null) {
                node = new ReservationNode(name, root);
                reservations.put(name, node);
            }
            return new HierarchicalScheduler(name) {
                @Override
                protected ReservationNode getRoot() {
                    return node;
                }
            };
        }
    }

    public long reserve(long start, long end) {
        synchronized (root) {
            validateInterval(start, end);
            return reserve(root, start, end, 0);
        }
    }

    private long reserve(ReservationNode node, long start, long end, long parentId) {
        synchronized (node) {
            if (checkConflicts(node, start, end)) return -1;

            long id = nextId++;
            node.reservations.put(id, new Reservation(start, end));
            if (node.parent != null) {
                node.parent.reserve(parentId + 1, start, end);
            }
            return id;
        }
    }

    private boolean checkConflicts(ReservationNode node, long start, long end) {
        for (Reservation reservation : node.reservations.values()) {
            if (start < reservation.end && end > reservation.start) return true;
        }
        return false;
    }

    public boolean cancel(long reservationId) {
        synchronized (root) {
            ReservationNode node = findReservation(reservationId);
            if (node == null || !node.reservations.containsKey(reservationId)) return false;
            node.reservations.remove(reservationId);
            return true;
        }
    }

    private ReservationNode findReservation(long reservationId) {
        synchronized (root) {
            return recursiveFind(root, reservationId);
        }
    }

    private ReservationNode recursiveFind(ReservationNode node, long reservationId) {
        synchronized (node) {
            if (node.reservations.containsKey(reservationId)) return node;
            for (ReservationNode child : node.children.values()) {
                ReservationNode result = recursiveFind(child, reservationId);
                if (result != null) return result;
            }
            return null;
        }
    }

    public Reservation[] snapshot() {
        synchronized (root) {
            return recursiveSnapshot(root);
        }
    }

    private Reservation[] recursiveSnapshot(ReservationNode node) {
        synchronized (node) {
            List<Reservation> list = new ArrayList<>();
            for (Map.Entry<Long, Reservation> entry : node.reservations.entrySet()) {
                list.add(new Reservation(entry.getKey(), entry.getValue().start, entry.getValue().end));
            }
            for (ReservationNode child : node.children.values()) {
                list.addAll(Arrays.asList(recursiveSnapshot(child)));
            }
            return list.toArray(new Reservation[list.size()]);
        }
    }

    private static void validateInterval(long start, long end) {
        if (start >= end) throw new IllegalArgumentException("Invalid interval");
    }

    private static class ReservationNode {
        final String name;
        final HierarchicalScheduler parent;
        final Map<String, ReservationNode> children;
        final Map<Long, Reservation> reservations;

        ReservationNode(String name, HierarchicalScheduler parent) {
            this.name = name;
            this.parent = parent;
            this.children = new HashMap<>();
            this.reservations = new HashMap<>();
        }
    }

    public static class Reservation {
        final long id;
        final long start;
        final long end;

        Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    private static class ReservationMap {
        final Map<String, ReservationNode> map = new HashMap<>();

        synchronized boolean.containsKey(String key) {
            return map.containsKey(key);
        }

        synchronized ReservationNode get(String key) {
            return map.get(key);
        }

        synchronized void put(String key, ReservationNode value) {
            map.put(key, value);
        }

        synchronized void remove(String key) {
            map.remove(key);
        }
    }
}
```
