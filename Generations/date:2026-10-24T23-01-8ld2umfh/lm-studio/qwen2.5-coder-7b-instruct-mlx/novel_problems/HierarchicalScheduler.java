import java.util.concurrent.atomic.AtomicLong;

@HierarchicalScheduler.ThreadSafe
class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final String name;
    private final HierarchicalScheduler parent;
    private final HierarchicalScheduler[] children;
    private final Reservation[] reservations;

    private static final int RESERVATION_SIZE = 1024;
    private static final int CHILDREN_SIZE = 256;

    public HierarchicalScheduler(String name) {
        this.name = name;
        this.parent = null;
        this.children = new HierarchicalScheduler[CHILDREN_SIZE];
        this.reservations = new Reservation[RESERVATION_SIZE];
    }

    public HierarchicalScheduler child(String name) {
        int idx = Math.abs(name.hashCode()) % CHILDREN_SIZE;
        synchronized (this) {
            if (children[idx] == null) {
                children[idx] = new HierarchicalScheduler(name, this);
            }
            return children[idx];
        }
    }

    private HierarchicalScheduler(String name, HierarchicalScheduler parent) {
        this.name = name;
        this.parent = parent;
        this.children = new HierarchicalScheduler[CHILDREN_SIZE];
        this.reservations = new Reservation[RESERVATION_SIZE];
    }

    public long reserve(long start, long end) {
        synchronized (this) {
            if (start >= end) {
                throw new IllegalArgumentException("Invalid interval");
            }
            for (Reservation r : reservations) {
                if (r != null && r.start < end && start < r.end) {
                    return -1;
                }
            }
            for (HierarchicalScheduler child : children) {
                if (child != null && child.reserve(start, end) != -1) {
                    return child.reservationId;
                }
            }
            long id = AtomicLong.getAndIncrement();
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i] == null) {
                    reservations[i] = new Reservation(id, start, end);
                    return id;
                }
            }
            throw new IllegalStateException("Reservation list is full");
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (this) {
            for (int i = 0; i < reservations.length; i++) {
                if (reservations[i] != null && reservations[i].id == reservationId) {
                    reservations[i] = null;
                    return true;
                }
            }
            for (HierarchicalScheduler child : children) {
                if (child != null && child.cancel(reservationId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public Record snapshot() {
        synchronized (this) {
            Record record = new Record(name, reservations);
            for (HierarchicalScheduler child : children) {
                if (child != null) {
                    record.addChild(child.snapshot());
                }
            }
            return record;
        }
    }

    private static class Reservation {
        long id;
        long start;
        long end;

        Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    private static class Record {
        String name;
        Reservation[] reservations;
        Record[] children;

        Record(String name, Reservation[] reservations) {
            this.name = name;
            this.reservations = reservations.clone();
        }

        void addChild(Record child) {
            if (children == null) {
                children = new Record[1];
            } else if (children.length == 0) {
                children = new Record[1];
            } else {
                Record[] newChildren = new Record[children.length + 1];
                System.arraycopy(children, 0, newChildren, 0, children.length);
                children = newChildren;
            }
            children[children.length - 1] = child;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Record{name='").append(name).append('\'');
            if (reservations != null) {
                sb.append(", reservations=").append(reservations);
            }
            if (children != null) {
                sb.append(", children=").append(children);
            }
            sb.append('}');
            return sb.toString();
        }
    }
}
