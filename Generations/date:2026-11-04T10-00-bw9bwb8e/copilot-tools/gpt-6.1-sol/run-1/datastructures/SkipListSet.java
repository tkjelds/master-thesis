/**
 * A thread-safe skip-list set of int values, including Integer.MIN_VALUE
 * and Integer.MAX_VALUE.
 *
 * All public operations synchronize on this instance and are linearizable.
 * The set has no fixed capacity. Duplicate additions and absent removals
 * return false immediately after acquiring the monitor; they never wait
 * for the set's contents to change.
 *
 * Search, insertion, and removal take expected O(log n) time.
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

    private final Node head = new Node(0, MAX_LEVEL);
    private int levels = 1;
    private int count;
    private int randomState = 0x6D2B79F5;

    public SkipListSet() {
    }

    /**
     * Adds value, returning true only if it was not already present.
     */
    public boolean add(int value) {
        synchronized (this) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
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

            int height = randomHeight();
            Node node = new Node(value, height);

            for (int level = levels; level < height; level++) {
                predecessors[level] = head;
            }

            for (int level = 0; level < height; level++) {
                node.next[level] = predecessors[level].next[level];
                predecessors[level].next[level] = node;
            }

            if (height > levels) {
                levels = height;
            }

            count++;
            return true;
        }
    }

    /**
     * Removes value, returning true only if it was present.
     */
    public boolean remove(int value) {
        synchronized (this) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value < value) {
                    current = current.next[level];
                }
                predecessors[level] = current;
            }

            Node node = current.next[0];
            if (node == null || node.value != value) {
                return false;
            }

            for (int level = 0; level < node.next.length; level++) {
                predecessors[level].next[level] = node.next[level];
            }

            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
            }

            count--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (this) {
            Node current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value < value) {
                    current = current.next[level];
                }
            }

            Node successor = current.next[0];
            return successor != null && successor.value == value;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            levels = 1;
            count = 0;
        }
    }

    /**
     * Returns an independent, ascending-order snapshot of the set.
     */
    public int[] toArray() {
        synchronized (this) {
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

    /*
     * Called only while holding this instance's monitor.
     * A nonzero xorshift state provides geometrically distributed heights.
     */
    private int randomHeight() {
        int bits = randomState;
        bits ^= bits << 13;
        bits ^= bits >>> 17;
        bits ^= bits << 5;
        randomState = bits;

        int height = 1;
        while (height < MAX_LEVEL && (bits & 1) == 0) {
            height++;
            bits >>>= 1;
        }
        return height;
    }
}
