@SkipListSet.ThreadSafe
public class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value) {
            this.value = value;
            this.next = (Node<T>[]) new Node[MAX_LEVEL];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null);
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public boolean add(E value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }

        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node[MAX_LEVEL];

            Node<E> current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
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
            Node<E> added = new Node<E>(value);
            for (int level = 0; level < nodeLevel; level++) {
                added.next[level] = update[level].next[level];
                update[level].next[level] = added;
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
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node[MAX_LEVEL];

            Node<E> current = head;
            for (int level = MAX_LEVEL - 1; level >= 0; level--) {
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

            for (int level = 0; level < MAX_LEVEL; level++) {
                if (update[level].next[level] == current) {
                    update[level].next[level] = current.next[level];
                }
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

    private int randomLevel() {
        randomState ^= randomState << 13;
        randomState ^= randomState >>> 7;
        randomState ^= randomState << 17;

        long bits = randomState;
        int level = 1;
        while (level < MAX_LEVEL && (bits & 1L) != 0L) {
            level++;
            bits >>>= 1;
        }
        return level;
    }
}
