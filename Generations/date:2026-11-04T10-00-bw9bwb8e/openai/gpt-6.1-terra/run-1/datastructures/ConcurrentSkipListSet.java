/**
 * A thread-safe, unbounded skip-list set.
 * Elements are identified by compareTo returning zero; null is not permitted.
 * Operations do not wait for elements or capacity.
 */
@ConcurrentSkipListSet.ThreadSafe
public final class ConcurrentSkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int height) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[height];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int levels = 1;
    private int size = 0;
    private int randomState = 0x6d2b79f5;

    private int randomLevel() {
        int height = 1;
        while (height < MAX_LEVEL) {
            int x = randomState;
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            randomState = x;
            if ((x & 1) != 0) {
                break;
            }
            height++;
        }
        return height;
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] newPredecessorArray() {
        return (Node<E>[]) new Node<?>[MAX_LEVEL];
    }

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E>[] predecessors = newPredecessorArray();
            Node<E> current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                predecessors[level] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("set size limit reached");
            }

            int height = randomLevel();
            Node<E> node = new Node<E>(value, height);

            if (height > levels) {
                for (int level = levels; level < height; level++) {
                    predecessors[level] = head;
                }
                levels = height;
            }

            for (int level = 0; level < height; level++) {
                node.next[level] = predecessors[level].next[level];
                predecessors[level].next[level] = node;
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
            Node<E>[] predecessors = newPredecessorArray();
            Node<E> current = head;

            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                predecessors[level] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int level = 0; level < candidate.next.length; level++) {
                predecessors[level].next[level] = candidate.next[level];
            }
            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
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
            for (int level = levels - 1; level >= 0; level--) {
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
            for (int level = 0; level < levels; level++) {
                head.next[level] = null;
            }
            levels = 1;
            size = 0;
        }
    }
}
