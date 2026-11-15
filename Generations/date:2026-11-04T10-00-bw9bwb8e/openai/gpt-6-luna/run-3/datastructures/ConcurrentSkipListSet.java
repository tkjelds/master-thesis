/**
 * A thread-safe skip-list set.
 *
 * All public operations are linearizable: a single intrinsic monitor protects
 * the skip list and its size. The set is unbounded; removing an absent element
 * returns false, and contains returns false for an absent element. Null elements
 * are not supported.
 */
@ConcurrentSkipListSet.ThreadSafe
public final class ConcurrentSkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int DEFAULT_MAX_LEVEL = 32;

    private final Object mutex = new Object();
    private final int maxLevel;
    private final Node<E> head;
    private int size;
    private long randomState;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[level];
        }
    }

    public ConcurrentSkipListSet() {
        this(DEFAULT_MAX_LEVEL);
    }

    public ConcurrentSkipListSet(int maxLevel) {
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel must be at least 1");
        }
        this.maxLevel = maxLevel;
        this.head = new Node<E>(null, maxLevel);
        this.randomState = 0x9E3779B97F4A7C15L ^ maxLevel;
        if (this.randomState == 0L) {
            this.randomState = 1L;
        }
    }

    public boolean add(E value) {
        requireNonNull(value);

        synchronized (mutex) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node<?>[maxLevel];

            Node<E> current = head;
            for (int level = maxLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            current = current.next[0];
            if (current != null && current.value.compareTo(value) == 0) {
                return false;
            }

            int nodeLevel = randomLevel();
            Node<E> node = new Node<E>(value, nodeLevel);
            for (int level = 0; level < nodeLevel; level++) {
                node.next[level] = update[level].next[level];
                update[level].next[level] = node;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireNonNull(value);

        synchronized (mutex) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node<?>[maxLevel];

            Node<E> current = head;
            for (int level = maxLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            current = current.next[0];
            if (current == null || current.value.compareTo(value) != 0) {
                return false;
            }

            for (int level = 0; level < current.next.length; level++) {
                update[level].next[level] = current.next[level];
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        requireNonNull(value);

        synchronized (mutex) {
            Node<E> current = head;
            for (int level = maxLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
            }

            current = current.next[0];
            return current != null && current.value.compareTo(value) == 0;
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            for (int level = 0; level < maxLevel; level++) {
                head.next[level] = null;
            }
            size = 0;
        }
    }

    /**
     * Returns a sorted snapshot. Changes made after this method returns do not
     * affect the returned array.
     */
    public Object[] toArray() {
        synchronized (mutex) {
            Object[] result = new Object[size];
            Node<E> current = head.next[0];
            int index = 0;
            while (current != null) {
                result[index++] = current.value;
                current = current.next[0];
            }
            return result;
        }
    }

    private int randomLevel() {
        int level = 1;
        while (level < maxLevel && (nextRandomLong() & 1L) == 0L) {
            level++;
        }
        return level;
    }

    private long nextRandomLong() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;
        return value;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }
}
