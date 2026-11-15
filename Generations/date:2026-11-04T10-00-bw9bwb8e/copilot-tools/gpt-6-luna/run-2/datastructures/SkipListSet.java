@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        private final T value;
        private final Node<T>[] next;

        @SuppressWarnings("unchecked")
        private Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[level];
        }
    }

    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int highestLevel = 1;
    private int size;
    private long randomState;

    public SkipListSet() {
        randomState = System.nanoTime()
                ^ ((long) System.identityHashCode(this) << 32);
        if (randomState == 0) {
            randomState = 0x9E3779B97F4A7C15L;
        }
    }

    public boolean add(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (this) {
            Node<E>[] update = new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int i = highestLevel - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].value.compareTo(value) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            if (level > highestLevel) {
                for (int i = highestLevel; i < level; i++) {
                    update[i] = head;
                }
                highestLevel = level;
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
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (this) {
            Node<E>[] update = new Node[MAX_LEVEL];
            Node<E> current = head;

            for (int i = highestLevel - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].value.compareTo(value) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node<E> candidate = current.next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i < candidate.next.length; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }

            while (highestLevel > 1 && head.next[highestLevel - 1] == null) {
                highestLevel--;
            }
            size--;
            return true;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (this) {
            Node<E> current = head;
            for (int i = highestLevel - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].value.compareTo(value) < 0) {
                    current = current.next[i];
                }
            }

            Node<E> candidate = current.next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < highestLevel; i++) {
                head.next[i] = null;
            }
            highestLevel = 1;
            size = 0;
        }
    }

    private int randomLevel() {
        long value = randomState;
        value ^= value << 13;
        value ^= value >>> 7;
        value ^= value << 17;
        randomState = value;

        int level = 1;
        while ((value & 1L) == 0 && level < MAX_LEVEL) {
            level++;
            value >>>= 1;
        }
        return level;
    }
}
