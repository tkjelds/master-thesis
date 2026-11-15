/**
 * A thread-safe skip-list set of int values, ordered numerically.
 *
 * Operations use a private intrinsic monitor and are linearizable.
 * The set is unbounded except by available memory. Missing values cause
 * contains/remove to return false; empty snapshots return an empty array.
 * No operation waits for the set to become nonempty.
 */
@SkipListSet.ThreadSafe
public final class SkipListSet {
    @interface ThreadSafe {}

    private static final int MAX_HEIGHT = 32;

    private static final class Monitor {
    }

    private static final class Node {
        final int value;
        final Node[] next;

        Node(int value, int height) {
            this.value = value;
            this.next = new Node[height];
        }
    }

    private final Monitor monitor = new Monitor();
    private final Node head = new Node(0, MAX_HEIGHT);

    // All mutable state, including node links, is guarded by monitor.
    private int height;
    private int count;
    private int randomState;

    public SkipListSet() {
        synchronized (monitor) {
            height = 1;
            count = 0;
            randomState = 0x6D2B79F5;
        }
    }

    public boolean add(int value) {
        synchronized (monitor) {
            Node[] predecessors = new Node[MAX_HEIGHT];
            Node current = head;

            for (int level = height - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value < value) {
                    current = current.next[level];
                }
                predecessors[level] = current;
            }

            Node successor = current.next[0];
            if (successor != null && successor.value == value) {
                return false;
            }

            int nodeHeight = randomHeight();
            Node node = new Node(value, nodeHeight);

            for (int level = height; level < nodeHeight; level++) {
                predecessors[level] = head;
            }

            for (int level = 0; level < nodeHeight; level++) {
                node.next[level] = predecessors[level].next[level];
                predecessors[level].next[level] = node;
            }

            if (nodeHeight > height) {
                height = nodeHeight;
            }
            count++;
            return true;
        }
    }

    public boolean remove(int value) {
        synchronized (monitor) {
            Node[] predecessors = new Node[MAX_HEIGHT];
            Node current = head;

            for (int level = height - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value < value) {
                    current = current.next[level];
                }
                predecessors[level] = current;
            }

            Node target = current.next[0];
            if (target == null || target.value != value) {
                return false;
            }

            for (int level = 0; level < target.next.length; level++) {
                predecessors[level].next[level] = target.next[level];
            }

            while (height > 1 && head.next[height - 1] == null) {
                height--;
            }

            count--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (monitor) {
            Node current = head;

            for (int level = height - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value < value) {
                    current = current.next[level];
                }
            }

            Node candidate = current.next[0];
            return candidate != null && candidate.value == value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int level = 0; level < MAX_HEIGHT; level++) {
                head.next[level] = null;
            }
            height = 1;
            count = 0;
        }
    }

    /**
     * Returns an independent, ascending snapshot of the set.
     */
    public int[] toArray() {
        synchronized (monitor) {
            int[] values = new int[count];
            Node current = head.next[0];
            int index = 0;

            while (current != null) {
                values[index++] = current.value;
                current = current.next[0];
            }

            return values;
        }
    }

    // Called only while holding monitor.
    private int randomHeight() {
        int bits = randomState;
        bits ^= bits << 13;
        bits ^= bits >>> 17;
        bits ^= bits << 5;
        randomState = bits;

        int result = 1;
        while (result < MAX_HEIGHT && (bits & 1) != 0) {
            result++;
            bits >>>= 1;
        }
        return result;
    }
}
