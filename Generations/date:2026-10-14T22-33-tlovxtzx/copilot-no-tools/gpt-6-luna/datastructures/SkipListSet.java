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

    private final Object mutex = new Object();
    private final Node<E> head = new Node<>(null, MAX_LEVEL);
    private int currentLevel = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
            Node<E>[] update = findPredecessors(value);
            Node<E> candidate = update[0].next[0];

            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            if (level > currentLevel) {
                for (int i = currentLevel; i < level; i++) {
                    update[i] = head;
                }
                currentLevel = level;
            }

            Node<E> node = new Node<>(value, level);
            for (int i = 0; i < level; i++) {
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
            Node<E>[] update = findPredecessors(value);
            Node<E> candidate = update[0].next[0];

            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i < currentLevel; i++) {
                if (update[i].next[i] != candidate) {
                    break;
                }
                update[i].next[i] = candidate.next[i];
            }

            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
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

        synchronized (mutex) {
            Node<E>[] update = findPredecessors(value);
            Node<E> candidate = update[0].next[0];
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

    public E first() {
        synchronized (mutex) {
            Node<E> node = head.next[0];
            if (node == null) {
                throw new IllegalStateException("set is empty");
            }
            return node.value;
        }
    }

    public Object[] toArray() {
        synchronized (mutex) {
            Object[] result = new Object[size];
            Node<E> node = head.next[0];
            for (int i = 0; i < size; i++) {
                result[i] = node.value;
                node = node.next[0];
            }
            return result;
        }
    }

    public void clear() {
        synchronized (mutex) {
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] findPredecessors(E value) {
        Node<E>[] update = (Node<E>[]) new Node<?>[MAX_LEVEL];
        Node<E> node = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (node.next[i] != null && node.next[i].value.compareTo(value) < 0) {
                node = node.next[i];
            }
            update[i] = node;
        }

        return update;
    }

    private int randomLevel() {
        int level = 1;
        long value = nextRandomLong();

        while (level < MAX_LEVEL && (value & 1L) == 0L) {
            level++;
            value >>>= 1;
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
