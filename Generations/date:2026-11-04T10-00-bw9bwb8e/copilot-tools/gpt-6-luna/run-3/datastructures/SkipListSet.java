@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] forward;

        @SuppressWarnings("unchecked")
        Node(T value) {
            this.value = value;
            this.forward = (Node<T>[]) new Node[MAX_LEVEL];
        }
    }

    private final Object mutex = new Object();
    private final Node<E> head = new Node<E>(null);
    private int level = 1;
    private int size;
    private long randomState;

    public SkipListSet() {
        randomState = System.nanoTime() ^ System.identityHashCode(this);
        if (randomState == 0L) {
            randomState = 0x9E3779B97F4A7C15L;
        }
    }

    public boolean add(E value) {
        requireNonNull(value);
        synchronized (mutex) {
            Node<E>[] update = newNodeArray();
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null
                        && current.forward[i].value.compareTo(value) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            current = current.forward[0];
            if (current != null && current.value.compareTo(value) == 0) {
                return false;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > level) {
                for (int i = level; i < nodeLevel; i++) {
                    update[i] = head;
                }
                level = nodeLevel;
            }

            Node<E> added = new Node<E>(value);
            for (int i = 0; i < nodeLevel; i++) {
                added.forward[i] = update[i].forward[i];
                update[i].forward[i] = added;
            }
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireNonNull(value);
        synchronized (mutex) {
            Node<E>[] update = newNodeArray();
            Node<E> current = head;

            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null
                        && current.forward[i].value.compareTo(value) < 0) {
                    current = current.forward[i];
                }
                update[i] = current;
            }

            current = current.forward[0];
            if (current == null || current.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i < level; i++) {
                if (update[i].forward[i] != current) {
                    break;
                }
                update[i].forward[i] = current.forward[i];
            }

            while (level > 1 && head.forward[level - 1] == null) {
                level--;
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        requireNonNull(value);
        synchronized (mutex) {
            Node<E> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.forward[i] != null
                        && current.forward[i].value.compareTo(value) < 0) {
                    current = current.forward[i];
                }
            }

            current = current.forward[0];
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
            for (int i = 0; i < level; i++) {
                head.forward[i] = null;
            }
            level = 1;
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] newNodeArray() {
        return (Node<E>[]) new Node[MAX_LEVEL];
    }

    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL && (nextRandom() & 1L) == 0L) {
            result++;
        }
        return result;
    }

    private long nextRandom() {
        long x = randomState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        randomState = x;
        return x;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }
}
