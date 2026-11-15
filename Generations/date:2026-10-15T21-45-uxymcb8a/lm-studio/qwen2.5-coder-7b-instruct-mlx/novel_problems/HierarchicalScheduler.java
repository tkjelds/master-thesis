@HierarchicalScheduler.ThreadSafe
public class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final String name;
    private final Node root;

    public HierarchicalScheduler(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name must be non-empty");
        }
        this.name = name;
        this.root = new Node(this, null);
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name must be non-empty");
        }
        return root.child(name);
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Invalid interval");
        }
        return root.reserve(start, end);
    }

    public boolean cancel(long reservationId) {
        return root.cancel(reservationId);
    }

    public Entry[] snapshot() {
        return root.snapshot();
    }

    private static class Node {
        private final HierarchicalScheduler scheduler;
        private final Node parent;
        private final String name;
        private Reservation reservation;
        private final Entry[] children;

        Node(HierarchicalScheduler scheduler, Node parent) {
            this.scheduler = scheduler;
            this.parent = parent;
            this.name = scheduler.name;
            this.reservation = null;
            this.children = new Entry[0];
        }

        Node child(String name) {
            synchronized (this) {
                for (Entry entry : children) {
                    if (entry.name.equals(name)) {
                        return entry.value;
                    }
                }
                Node child = new Node(scheduler, this);
                Entry[] newChildren = new Entry[children.length + 1];
                System.arraycopy(children, 0, newChildren, 0, children.length);
                newChildren[children.length] = new Entry(name, child);
                this.children = newChildren;
                return child;
            }
        }

        long reserve(long start, long end) {
            synchronized (this) {
                if (reservation != null && (start < reservation.end || end > reservation.start)) {
                    return -1;
                }
                for (Node child : children) {
                    if (child.reservation != null && (start < child.reservation.end || end > child.reservation.start)) {
                        return -1;
                    }
                }
                long id = getUniqueId();
                reservation = new Reservation(id, start, end);
                return id;
            }
        }

        boolean cancel(long reservationId) {
            synchronized (this) {
                if (reservation != null && reservation.id == reservationId) {
                    reservation = null;
                    return true;
                }
                for (Node child : children) {
                    if (child.cancel(reservationId)) {
                        return true;
                    }
                }
                return false;
            }
        }

        Entry[] snapshot() {
            synchronized (this) {
                Entry[] result = new Entry[children.length];
                for (int i = 0; i < children.length; i++) {
                    result[i] = new Entry(children[i].name, children[i]);
                }
                return result;
            }
        }

        private long getUniqueId() {
            synchronized (scheduler.root) {
                return scheduler.root.getNextId();
            }
        }

        private long getNextId() {
            return (long) (Math.random() * Long.MAX_VALUE);
        }
    }

    private static class Entry {
        final String name;
        final Node value;

        Entry(String name, Node value) {
            this.name = name;
            this.value = value;
        }
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
