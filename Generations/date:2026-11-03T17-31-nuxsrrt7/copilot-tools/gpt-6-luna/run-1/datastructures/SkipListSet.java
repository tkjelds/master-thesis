@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 31;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[level + 1];
        }
    }

    private final Object monitor = new Object();
    private final Node<E> head = new Node<>(null, MAX_LEVEL);
    private int currentLevel;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (monitor) {
            Node<E>[] update = findPredecessors(value);
            Node<E> candidate = update[0].next[0];

            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            if (level > currentLevel) {
                for (int i = currentLevel + 1; i <= level; i++) {
                    update[i] = head;
                }
                currentLevel = level;
            }

            Node<E> inserted = new Node<>(value, level);
            for (int i = 0; i <= level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }

            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (monitor) {
            Node<E>[] update = findPredecessors(value);
            Node<E> candidate = update[0].next[0];

            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i <= currentLevel; i++) {
                if (update[i].next[i] != candidate) {
                    break;
                }
                update[i].next[i] = candidate.next[i];
            }

            while (currentLevel > 0 && head.next[currentLevel] == null) {
                currentLevel--;
            }

            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (monitor) {
            Node<E>[] update = findPredecessors(value);
            Node<E> candidate = update[0].next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i <= currentLevel; i++) {
                head.next[i] = null;
            }
            currentLevel = 0;
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] findPredecessors(E value) {
        Node<E>[] update = (Node<E>[]) new Node<?>[MAX_LEVEL + 1];
        Node<E> cursor = head;

        for (int level = currentLevel; level >= 0; level--) {
            while (cursor.next[level] != null
                    && cursor.next[level].value.compareTo(value) < 0) {
                cursor = cursor.next[level];
            }
            update[level] = cursor;
        }

        return update;
    }

    private int randomLevel() {
        long x = randomState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        randomState = x;

        int level = 0;
        while (level < MAX_LEVEL && (x & 1L) == 0L) {
            level++;
            x >>>= 1;
        }
        return level;
    }
}
