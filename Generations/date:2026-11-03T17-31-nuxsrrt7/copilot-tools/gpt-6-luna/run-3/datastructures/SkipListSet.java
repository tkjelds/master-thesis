@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node {
        final Object value;
        final Node[] next;

        Node(Object value, int level) {
            this.value = value;
            this.next = new Node[level];
        }
    }

    private final Object lock = new Object();
    private final Node head = new Node(null, MAX_LEVEL);
    private int level = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L ^ System.nanoTime();

    public boolean add(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (lock) {
            Node[] update = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && compare(current.next[i].value, value) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node candidate = update[0].next[0];
            if (candidate != null && compare(candidate.value, value) == 0) {
                return false;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > level) {
                for (int i = level; i < nodeLevel; i++) {
                    update[i] = head;
                }
                level = nodeLevel;
            }

            Node node = new Node(value, nodeLevel);
            for (int i = 0; i < nodeLevel; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            Node[] update = new Node[MAX_LEVEL];
            Node current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && compare(current.next[i].value, value) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node candidate = update[0].next[0];
            if (candidate == null || compare(candidate.value, value) != 0) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }

            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            Node current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && compare(current.next[i].value, value) < 0) {
                    current = current.next[i];
                }
            }

            Node candidate = current.next[0];
            return candidate != null && compare(candidate.value, value) == 0;
        }
    }

    public int size() {
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    public E first() {
        synchronized (lock) {
            return head.next[0] == null ? null : valueOf(head.next[0]);
        }
    }

    public E last() {
        synchronized (lock) {
            Node current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : valueOf(current);
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node current = head.next[0];
            for (int i = 0; i < result.length; i++) {
                result[i] = current.value;
                current = current.next[0];
            }
            return result;
        }
    }

    private int compare(Object left, E right) {
        return valueOfNode(left).compareTo(right);
    }

    private E valueOfNode(Object value) {
        return (E) value;
    }

    private E valueOf(Node node) {
        return (E) node.value;
    }

    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL && (nextRandomLong() & 1L) == 0L) {
            result++;
        }
        return result;
    }

    private long nextRandomLong() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;
        return value;
    }
}
