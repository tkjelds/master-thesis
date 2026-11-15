/**
 * A thread-safe skip-list set of int values.
 *
 * All operations synchronize on a private monitor. Operations never wait for
 * elements or capacity: removing an absent value returns false, as does adding
 * an existing value. Capacity is limited only by available memory.
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
    private int height = 1;
    private long count;
    private int randomState = 0x9E3779B9;

    public SkipListSet() {
    }

    public boolean add(int value) {
        synchronized (head) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node candidate = findCandidate(value, predecessors);
            if (candidate != null && candidate.value == value) {
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
        synchronized (head) {
            Node[] predecessors = new Node[MAX_LEVEL];
            Node candidate = findCandidate(value, predecessors);
            if (candidate == null || candidate.value != value) {
                return false;
            }

            for (int level = 0; level < candidate.next.length; level++) {
                predecessors[level].next[level] = candidate.next[level];
            }

            while (height > 1 && head.next[height - 1] == null) {
                height--;
            }
            count--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (head) {
            Node candidate = findCandidate(value, null);
            return candidate != null && candidate.value == value;
        }
    }

    /**
     * Returns the size, capped at the largest int value.
     * Use exactSize() for an uncapped count.
     */
    public int size() {
        synchronized (head) {
            return count > 2147483647L ? 2147483647 : (int) count;
        }
    }

    public long exactSize() {
        synchronized (head) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return count == 0;
        }
    }

    public void clear() {
        synchronized (head) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            height = 1;
            count = 0;
        }
    }

    // Caller must hold the head monitor.
    private Node findCandidate(int value, Node[] predecessors) {
        Node current = head;
        for (int level = height - 1; level >= 0; level--) {
            Node next = current.next[level];
            while (next != null && next.value < value) {
                current = next;
                next = current.next[level];
            }
            if (predecessors != null) {
                predecessors[level] = current;
            }
        }
        return current.next[0];
    }

    // Caller must hold the head monitor.
    private int randomHeight() {
        int bits = randomState;
        bits ^= bits << 13;
        bits ^= bits >>> 17;
        bits ^= bits << 5;
        randomState = bits;

        int result = 1;
        while (result < MAX_LEVEL && (bits & 1) != 0) {
            result++;
            bits >>>= 1;
        }
        return result;
    }
}
