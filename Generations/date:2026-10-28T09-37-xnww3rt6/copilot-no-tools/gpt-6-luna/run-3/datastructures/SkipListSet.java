@SkipListSet.ThreadSafe
public final class SkipListSet<T extends Comparable<? super T>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<E> {
        final E value;
        final Node<E>[] next;

        @SuppressWarnings("unchecked")
        Node(E value, int level) {
            this.value = value;
            this.next = (Node<E>[]) new Node<?>[level];
        }
    }

    private final Object mutex = new Object();
    private final Node<T> head = new Node<>(null, MAX_LEVEL);
    private int size;
    private long randomState;

    public SkipListSet() {
        randomState = System.nanoTime() ^ System.identityHashCode(this);
        if (randomState == 0) {
            randomState = 0x9E3779B97F4A7C15L;
        }
    }

    public boolean add(T value) {
        requireNonNull(value);

        synchronized (mutex) {
            Node<T>[] update = findPredecessors(value);
            Node<T> candidate = update[0].next[0];

            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            Node<T> inserted = new Node<>(value, level);

            for (int i = 0; i < level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }

            size++;
            return true;
        }
    }

    public boolean remove(T value) {
        requireNonNull(value);

        synchronized (mutex) {
            Node<T>[] update = findPredecessors(value);
            Node<T> candidate = update[0].next[0];

            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                update[i].next[i] = candidate.next[i];
            }

            size--;
            return true;
        }
    }

    public boolean contains(T value) {
        requireNonNull(value);

        synchronized (mutex) {
            Node<T>[] update = findPredecessors(value);
            Node<T> candidate = update[0].next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            size = 0;
        }
    }

    public T first() {
        synchronized (mutex) {
            return head.next[0] == null ? null : head.next[0].value;
        }
    }

    public T last() {
        synchronized (mutex) {
            Node<T> current = head;
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : current.value;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<T>[] findPredecessors(T value) {
        Node<T>[] update = (Node<T>[]) new Node<?>[MAX_LEVEL];
        Node<T> current = head;

        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].value.compareTo(value) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        return update;
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && (nextRandomLong() & 1L) == 0L) {
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
            throw new IllegalArgumentException("null values are not supported");
        }
    }
}
