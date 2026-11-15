/**
 * A linearizable, unbounded set of int values implemented as a skip list.
 * Operations do not wait for elements or capacity. All operations use one
 * intrinsic lock, so concurrent calls may wait for another call to finish.
 */
@SkipListSet.ThreadSafe
public final class SkipListSet {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node {
        final int value;
        final Node[] next;

        Node(int value, int height) {
            this.value = value;
            this.next = new Node[height];
        }
    }

    private final Object lock = new Object();
    private final Node head = new Node(0, MAX_LEVEL);
    private int level = 1;
    private long size = 0;
    private int randomState = 0x6d2b79f5;

    private int randomHeight() {
        int x = randomState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        randomState = x;

        int height = 1;
        while (height < MAX_LEVEL && (x & 1) == 0) {
            height++;
            x >>>= 1;
        }
        return height;
    }

    /** Returns true if the value was newly inserted. */
    public boolean add(int value) {
        synchronized (lock) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].value < value) {
                    current = current.next[i];
                }
                predecessors[i] = current;
            }

            Node candidate = current.next[0];
            if (candidate != null && candidate.value == value) {
                return false;
            }

            int height = randomHeight();
            Node node = new Node(value, height);

            for (int i = level; i < height; i++) {
                predecessors[i] = head;
            }
            for (int i = 0; i < height; i++) {
                node.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = node;
            }
            if (height > level) {
                level = height;
            }
            size++;
            return true;
        }
    }

    /** Returns true if the value was present and removed. */
    public boolean remove(int value) {
        synchronized (lock) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].value < value) {
                    current = current.next[i];
                }
                predecessors[i] = current;
            }

            Node candidate = current.next[0];
            if (candidate == null || candidate.value != value) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                predecessors[i].next[i] = candidate.next[i];
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (lock) {
            Node current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].value < value) {
                    current = current.next[i];
                }
            }
            Node candidate = current.next[0];
            return candidate != null && candidate.value == value;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }
}
