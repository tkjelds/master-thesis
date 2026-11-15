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
            this.next = (Node<T>[]) new Node[level + 1];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int currentLevel;
    private int size;
    private long randomState;

    public SkipListSet() {
        randomState = System.nanoTime() ^ (long) System.identityHashCode(this);
        if (randomState == 0L) {
            randomState = 0x9E3779B97F4A7C15L;
        }
    }

    public boolean add(E value) {
        requireNonNull(value);

        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node[MAX_LEVEL + 1];

            Node<E> current = head;
            for (int level = currentLevel; level >= 0; level--) {
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
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Set is too large");
            }

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int level = currentLevel + 1; level <= nodeLevel; level++) {
                    update[level] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<E> added = new Node<E>(value, nodeLevel);
            for (int level = 0; level <= nodeLevel; level++) {
                added.next[level] = update[level].next[level];
                update[level].next[level] = added;
            }

            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireNonNull(value);

        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] update = (Node<E>[]) new Node[MAX_LEVEL + 1];

            Node<E> current = head;
            for (int level = currentLevel; level >= 0; level--) {
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

            for (int level = 0; level <= currentLevel; level++) {
                if (update[level].next[level] != current) {
                    break;
                }
                update[level].next[level] = current.next[level];
            }

            while (currentLevel > 0 && head.next[currentLevel] == null) {
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
            for (int level = currentLevel; level >= 0; level--) {
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
            for (int level = 0; level <= MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            currentLevel = 0;
            size = 0;
        }
    }

    public E first() {
        synchronized (lock) {
            return head.next[0] == null ? null : head.next[0].value;
        }
    }

    public E last() {
        synchronized (lock) {
            Node<E> current = head;
            for (int level = currentLevel; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }
            return current == head ? null : current.value;
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
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;

        int level = 0;
        while (level < MAX_LEVEL && (value & 1L) != 0L) {
            level++;
            value >>>= 1;
        }
        return level;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }
    }
}
