@ConcurrentSkipListSet.ThreadSafe
public class ConcurrentSkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);

    // All fields below are accessed only while holding lock.
    private int currentLevel = 1;
    private int size;
    private int randomState = 0x6D2B79F5;

    private static final class Node<T> {
        final T value;
        final Node<T>[] forward;

        @SuppressWarnings("unchecked")
        Node(T value, int level) {
            this.value = value;
            this.forward = (Node<T>[]) new Node[level];
        }
    }

    public ConcurrentSkipListSet() {
    }

    public boolean add(E value) {
        requireNonNull(value);

        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.forward[level] != null
                        && current.forward[level].value.compareTo(value) < 0) {
                    current = current.forward[level];
                }
                update[level] = current;
            }

            Node<E> candidate = update[0].forward[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int level = currentLevel; level < nodeLevel; level++) {
                    update[level] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<E> node = new Node<E>(value, nodeLevel);
            for (int level = 0; level < nodeLevel; level++) {
                node.forward[level] = update[level].forward[level];
                update[level].forward[level] = node;
            }

            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireNonNull(value);

        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.forward[level] != null
                        && current.forward[level].value.compareTo(value) < 0) {
                    current = current.forward[level];
                }
                update[level] = current;
            }

            Node<E> candidate = update[0].forward[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int level = 0; level < candidate.forward.length; level++) {
                if (update[level].forward[level] == candidate) {
                    update[level].forward[level] = candidate.forward[level];
                }
            }

            while (currentLevel > 1 && head.forward[currentLevel - 1] == null) {
                currentLevel--;
            }

            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        requireNonNull(value);

        synchronized (lock) {
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.forward[level] != null
                        && current.forward[level].value.compareTo(value) < 0) {
                    current = current.forward[level];
                }
            }

            Node<E> candidate = current.forward[0];
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
            for (int level = 0; level < currentLevel; level++) {
                head.forward[level] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    private int randomLevel() {
        int value = randomState;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        randomState = value;

        int level = 1;
        while (level < MAX_LEVEL && (value & 1) == 0) {
            level++;
            value >>>= 1;
        }
        return level;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("null values are not supported");
        }
    }
}
