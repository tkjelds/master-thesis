@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[level];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int level = 1;
    private int size = 0;
    private long randomState = 0x9E3779B97F4A7C15L;

    /*
     * Elements must be non-null and have a stable natural ordering.
     * Comparison must not modify this set. Operations do not wait for
     * elements or capacity; add and remove return false when unchanged.
     */
    public SkipListSet() {
    }

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E>[] predecessors = newNodeArray();
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && value.compareTo(current.next[i].value) > 0) {
                    current = current.next[i];
                }
                predecessors[i] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate != null && value.compareTo(candidate.value) == 0) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("set is too large");
            }

            int newLevel = randomLevel();
            Node<E> node = new Node<E>(value, newLevel);

            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    predecessors[i] = head;
                }
                level = newLevel;
            }

            for (int i = 0; i < newLevel; i++) {
                node.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = node;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E>[] predecessors = newNodeArray();
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && value.compareTo(current.next[i].value) > 0) {
                    current = current.next[i];
                }
                predecessors[i] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate == null || value.compareTo(candidate.value) != 0) {
                return false;
            }

            for (int i = 0; i < level; i++) {
                if (predecessors[i].next[i] == candidate) {
                    predecessors[i].next[i] = candidate.next[i];
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
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && value.compareTo(current.next[i].value) > 0) {
                    current = current.next[i];
                }
            }
            Node<E> candidate = current.next[0];
            return candidate != null && value.compareTo(candidate.value) == 0;
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
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] newNodeArray() {
        return (Node<E>[]) new Node<?>[MAX_LEVEL];
    }

    private int randomLevel() {
        long x = randomState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        randomState = x;

        int result = 1;
        while (result < MAX_LEVEL && (x & 1L) != 0) {
            result++;
            x >>>= 1;
        }
        return result;
    }
}
