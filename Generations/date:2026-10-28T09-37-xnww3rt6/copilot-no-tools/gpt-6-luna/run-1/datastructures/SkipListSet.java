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
            this.next = (Node<T>[]) new Node<?>[level + 1];
        }
    }

    private final Object mutex = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL - 1);
    private int size;
    private long randomState = System.nanoTime() ^ 0x9E3779B97F4A7C15L;

    public SkipListSet() {
        if (randomState == 0L) {
            randomState = 0x9E3779B97F4A7C15L;
        }
    }

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
            Node<E>[] update = newNodeArray();
            Node<E> current = head;

            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<E> candidate = update[0].next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            Node<E> node = new Node<E>(value, level);
            for (int i = 0; i <= level; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
            Node<E>[] update = newNodeArray();
            Node<E> current = head;

            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<E> candidate = update[0].next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
            Node<E> current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] newNodeArray() {
        return (Node<E>[]) new Node<?>[MAX_LEVEL];
    }

    private int randomLevel() {
        int level = 0;
        while (level < MAX_LEVEL - 1 && (nextRandomLong() & 1L) == 0L) {
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
}
