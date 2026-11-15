```java
public class HierarchicalScheduler {
    private final String name;
    private final Node childMap = new Node(this, "", 0);
    private long nextId = 1;
    private final Object monitor = new Object();

    public HierarchicalScheduler(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Name must not be empty");
        this.name = name;
    }

    public HierarchicalScheduler child(String name) {
        synchronized (monitor) {
            return childMap.child(name);
        }
    }

    public long reserve(long start, long end) {
        synchronized (monitor) {
            return childMap.reserve(start, end);
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (monitor) {
            return childMap.cancel(reservationId);
        }
    }

    public Reservation[] snapshot() {
        synchronized (monitor) {
            return childMap.snapshot();
        }
    }

    private static class Node {
        private final HierarchicalScheduler parent;
        private final String name;
        private final Node[] children = new Node[256];
        private Reservation[] reservations = new Reservation[128];
        private int reservationCount;

        public Node(HierarchicalScheduler parent, String name, long parentId) {
            this.parent = parent;
            this.name = name;
            this.reservationCount = 0;
        }

        public HierarchicalScheduler child(String name) {
            int hash = name.hashCode() & 0xFF;
            if (children[hash] == null) {
                children[hash] = new Node(parent, name, parent.nextId++);
            }
            return children[hash];
        }

        public long reserve(long start, long end) {
            if (start >= end) throw new IllegalArgumentException("Invalid interval");
            for (int i = 0; i < reservationCount; i++) {
                Reservation r = reservations[i];
                if (start < r.end && end > r.start) return -1;
            }
            long id = parent.nextId++;
            Reservation newReservation = new Reservation(id, start, end);
            for (int i = 0; i < reservationCount; i++) {
                Reservation r = reservations[i];
                if (start <= r.start && end >= r.end) return -1;
            }
            Reservation[] newReservations = new Reservation[reservationCount + 1];
            System.arraycopy(reservations, 0, newReservations, 0, reservationCount);
            newReservations[reservationCount] = newReservation;
            reservations = newReservations;
            reservationCount++;
            return id;
        }

        public boolean cancel(long reservationId) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    System.arraycopy(reservations, i + 1, reservations, i, reservationCount - i - 1);
                    reservationCount--;
                    return true;
                }
            }
            return false;
        }

        public Reservation[] snapshot() {
            Reservation[] result = new Reservation[reservationCount];
            System.arraycopy(reservations, 0, result, 0, reservationCount);
            return result;
        }
    }

    private static class Reservation {
        final long id;
        final long start;
        final long end;

        public Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }
}
```
