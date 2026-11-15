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
            this.next = (Node<T>[]) new Node[level];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int currentLevel = 1;
    private int size;
    private int randomState = 0x6D2B79F5;

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E>[] update = new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<E> candidate = current.next[0];
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

            Node<E> node = new Node<E>(value, level);
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

        synchronized (lock) {
            Node<E>[] update = new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int level = 0; level < currentLevel; level++) {
                if (update[level].next[level] != candidate) {
                    break;
                }
                update[level].next[level] = candidate.next[level];
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

        synchronized (lock) {
            Node<E> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
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
        int value = randomState;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        randomState = value;

        int level = 1;
        while ((value & 1) != 0 && level < MAX_LEVEL) {
            level++;
            value >>>= 1;
        }
        return level;
    }
}
