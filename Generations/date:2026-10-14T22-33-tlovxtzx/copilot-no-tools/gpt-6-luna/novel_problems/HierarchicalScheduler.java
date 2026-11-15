@HierarchicalScheduler.ThreadSafe
public final class HierarchicalScheduler {
    @interface ThreadSafe {}

    private final TreeState state;
    private final HierarchicalScheduler parent;
    private final String name;

    public HierarchicalScheduler() {
        this.state = new TreeState();
        this.parent = null;
        this.name = null;
    }

    private HierarchicalScheduler(
            TreeState state, HierarchicalScheduler parent, String name) {
        this.state = state;
        this.parent = parent;
        this.name = name;
    }

    public HierarchicalScheduler child(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Child name must be nonempty");
        }

        synchronized (state) {
            for (int i = 0; i < childCount; i++) {
                if (children[i].name.equals(name)) {
                    return children[i];
                }
            }

            HierarchicalScheduler child =
                    new HierarchicalScheduler(state, this, name);
            if (childCount == children.length) {
                children = grow(children, childCount);
            }
            children[childCount++] = child;
            return child;
        }
    }

    public long reserve(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Reservation must have start < end");
        }

        synchronized (state) {
            for (HierarchicalScheduler node = this;
                    node != null;
                    node = node.parent) {
                if (node.hasOverlap(start, end)) {
                    return -1L;
                }
            }

            if (hasDescendantOverlap(start, end)) {
                return -1L;
            }

            if (state.idsExhausted) {
                throw new IllegalStateException("Reservation ID space exhausted");
            }

            long id = state.nextId;
            if (id == Long.MAX_VALUE) {
                state.idsExhausted = true;
            } else {
                state.nextId++;
            }

            if (reservationCount == reservations.length) {
                reservations = grow(reservations, reservationCount);
            }
            reservations[reservationCount++] = new ReservationData(id, start, end);
            return id;
        }
    }

    public boolean cancel(long reservationId) {
        synchronized (state) {
            for (int i = 0; i < reservationCount; i++) {
                if (reservations[i].id == reservationId) {
                    int remaining = reservationCount - i - 1;
                    if (remaining > 0) {
                        System.arraycopy(reservations, i + 1, reservations, i, remaining);
                    }
                    reservations[--reservationCount] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (state) {
            int count = countSubtree();
            NodeSnapshot[] result = new NodeSnapshot[count];
            fillSnapshot(result);
            return new Snapshot(result);
        }
    }

    private boolean hasOverlap(long start, long end) {
        for (int i = 0; i < reservationCount; i++) {
            ReservationData reservation = reservations[i];
            if (start < reservation.end && reservation.start < end) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDescendantOverlap(long start, long end) {
        Object[] stack = new Object[16];
        int size = 0;

        for (int i = childCount - 1; i >= 0; i--) {
            if (size == stack.length) {
                stack = grow(stack, size);
            }
            stack[size++] = children[i];
        }

        while (size > 0) {
            HierarchicalScheduler node =
                    (HierarchicalScheduler) stack[--size];
            if (node.hasOverlap(start, end)) {
                return true;
            }
            for (int i = node.childCount - 1; i >= 0; i--) {
                if (size == stack.length) {
                    stack = grow(stack, size);
                }
                stack[size++] = node.children[i];
            }
        }
        return false;
    }

    private int countSubtree() {
        Object[] stack = new Object[16];
        int size = 1;
        int count = 0;
        stack[0] = this;

        while (size > 0) {
            HierarchicalScheduler node =
                    (HierarchicalScheduler) stack[--size];
            count++;
            for (int i = node.childCount - 1; i >= 0; i--) {
                if (size == stack.length) {
                    stack = grow(stack, size);
                }
                stack[size++] = node.children[i];
            }
        }
        return count;
    }

    private void fillSnapshot(NodeSnapshot[] result) {
        Object[] stack = new Object[16];
        int size = 1;
        int index = 0;
        stack[0] = this;

        while (size > 0) {
            HierarchicalScheduler node =
                    (HierarchicalScheduler) stack[--size];
            Reservation[] detachedReservations =
                    new Reservation[node.reservationCount];
            for (int i = 0; i < node.reservationCount; i++) {
                ReservationData reservation = node.reservations[i];
                detachedReservations[i] = new Reservation(
                        reservation.id, reservation.start, reservation.end);
            }
            result[index++] = new NodeSnapshot(
                    node.path(), detachedReservations);

            for (int i = node.childCount - 1; i >= 0; i--) {
                if (size == stack.length) {
                    stack = grow(stack, size);
                }
                stack[size++] = node.children[i];
            }
        }
    }

    private String path() {
        int depth = 0;
        for (HierarchicalScheduler node = this;
                node.parent != null;
                node = node.parent) {
            depth++;
        }

        if (depth == 0) {
            return "/";
        }

        String[] segments = new String[depth];
        HierarchicalScheduler node = this;
        for (int i = depth - 1; i >= 0; i--) {
            segments[i] = node.name;
            node = node.parent;
        }

        int length = 0;
        for (int i = 0; i < segments.length; i++) {
            length++;
            String segment = segments[i];
            for (int j = 0; j < segment.length(); j++) {
                char c = segment.charAt(j);
                length += (c == '/' || c == '%') ? 3 : 1;
            }
        }

        char[] chars = new char[length];
        int position = 0;
        for (int i = 0; i < segments.length; i++) {
            chars[position++] = '/';
            String segment = segments[i];
            for (int j = 0; j < segment.length(); j++) {
                char c = segment.charAt(j);
                if (c == '/') {
                    chars[position++] = '%';
                    chars[position++] = '2';
                    chars[position++] = 'F';
                } else if (c == '%') {
                    chars[position++] = '%';
                    chars[position++] = '2';
                    chars[position++] = '5';
                } else {
                    chars[position++] = c;
                }
            }
        }
        return new String(chars);
    }

    private static HierarchicalScheduler[] grow(
            HierarchicalScheduler[] array, int size) {
        HierarchicalScheduler[] grown =
                new HierarchicalScheduler[capacity(array.length, size)];
        System.arraycopy(array, 0, grown, 0, size);
        return grown;
    }

    private static ReservationData[] grow(ReservationData[] array, int size) {
        ReservationData[] grown =
                new ReservationData[capacity(array.length, size)];
        System.arraycopy(array, 0, grown, 0, size);
        return grown;
    }

    private static Object[] grow(Object[] array, int size) {
        Object[] grown = new Object[capacity(array.length, size)];
        System.arraycopy(array, 0, grown, 0, size);
        return grown;
    }

    private static int capacity(int current, int size) {
        if (current == 0) {
            return 4;
        }
        if (current > Integer.MAX_VALUE / 2) {
            return Integer.MAX_VALUE;
        }
        return current * 2;
    }

    private final HierarchicalScheduler[] initialChildren =
            new HierarchicalScheduler[0];
    private HierarchicalScheduler[] children = initialChildren;
    private int childCount;

    private ReservationData[] reservations = new ReservationData[0];
    private int reservationCount;

    private static final class TreeState {
        private long nextId = 1L;
        private boolean idsExhausted;
    }

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
        private final long id;
        private final long start;
        private final long end;

        private Reservation(long id, long start, long end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }

        public long id() {
            return id;
        }

        public long start() {
            return start;
        }

        public long end() {
            return end;
        }
    }

    public static final class NodeSnapshot {
        private final String path;
        private final Reservation[] reservations;

        private NodeSnapshot(String path, Reservation[] reservations) {
            this.path = path;
            this.reservations = reservations;
        }

        public String path() {
            return path;
        }

        public Reservation[] reservations() {
            Reservation[] copy = new Reservation[reservations.length];
            System.arraycopy(reservations, 0, copy, 0, reservations.length);
            return copy;
        }
    }

    public static final class Snapshot {
        private final NodeSnapshot[] nodes;

        private Snapshot(NodeSnapshot[] nodes) {
            this.nodes = nodes;
        }

        public NodeSnapshot[] nodes() {
            NodeSnapshot[] copy = new NodeSnapshot[nodes.length];
            System.arraycopy(nodes, 0, copy, 0, nodes.length);
            return copy;
        }
    }
}
