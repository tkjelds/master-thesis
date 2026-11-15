@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int levels) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[levels];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int size;
    private int randomState = 0x6d2b79f5;

    // The set is unbounded except for the maximum representable size.
    // Operations do not wait for elements or capacity.
    public boolean add(E value) {
        requireValue(value);
        synchronized (lock) {
            Node<E>[] predecessors = findPredecessors(value);
            Node<E> candidate = predecessors[0].next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum set size reached");
            }

            int levels = randomLevel();
            Node<E> node = new Node<E>(value, levels);
            for (int level = 0; level < levels; level++) {
                node.next[level] = predecessors[level].next[level];
                predecessors[level].next[level] = node;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireValue(value);
        synchronized (lock) {
            Node<E>[] predecessors = findPredecessors(value);
            Node<E> candidate = predecessors[0].next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int level = 0; level < candidate.next.length; level++) {
                predecessors[level].next[level] = candidate.next[level];
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        requireValue(value);
        synchronized (lock) {
            Node<E> current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
            }
            Node<E> candidate = current.next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
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
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            size = 0;
        }
    }

    private void requireValue(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] findPredecessors(E value) {
        Node<E>[] predecessors = (Node<E>[]) new Node<?>[MAX_LEVEL];
        Node<E> current = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].value.compareTo(value) < 0) {
                current = current.next[level];
            }
            predecessors[level] = current;
        }
        return predecessors;
    }

    private int randomLevel() {
        int levels = 1;
        while (levels < MAX_LEVEL && (nextRandom() & 1) != 0) {
            levels++;
        }
        return levels;
    }

    private int nextRandom() {
        int x = randomState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        randomState = x;
        return x;
    }
}
