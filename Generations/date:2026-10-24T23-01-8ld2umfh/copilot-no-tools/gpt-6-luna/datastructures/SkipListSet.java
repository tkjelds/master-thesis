@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int height) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[height];
        }
    }

    private final Object lock = new Object();
    private final Node<E> head = new Node<E>(null, MAX_LEVEL);
    private int currentLevel = 1;
    private int elementCount;
    private int randomState = 0x6D2B79F5;

    public SkipListSet() {
    }

    public boolean add(E value) {
        requireNonNull(value);

        synchronized (lock) {
            Node<?>[] update = new Node<?>[MAX_LEVEL];
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

            int height = randomHeight();
            if (height > currentLevel) {
                for (int level = currentLevel; level < height; level++) {
                    update[level] = head;
                }
                currentLevel = height;
            }

            Node<E> inserted = new Node<E>(value, height);
            for (int level = 0; level < height; level++) {
                Node<E> predecessor = nodeAt(update[level]);
                inserted.next[level] = predecessor.next[level];
                predecessor.next[level] = inserted;
            }

            elementCount++;
            return true;
        }
    }

    public boolean remove(E value) {
        requireNonNull(value);

        synchronized (lock) {
            Node<?>[] update = new Node<?>[MAX_LEVEL];
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
                Node<E> predecessor = nodeAt(update[level]);
                if (predecessor.next[level] != candidate) {
                    break;
                }
                predecessor.next[level] = candidate.next[level];
            }

            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }

            elementCount--;
            return true;
        }
    }

    public boolean contains(E value) {
        requireNonNull(value);

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
            return elementCount;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return elementCount == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            currentLevel = 1;
            elementCount = 0;
        }
    }

    private int randomHeight() {
        int value = randomState;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        randomState = value;

        int height = 1;
        while (height < MAX_LEVEL && (value & 1) == 0) {
            height++;
            value >>>= 1;
        }
        return height;
    }

    @SuppressWarnings("unchecked")
    private Node<E> nodeAt(Node<?> node) {
        return (Node<E>) node;
    }

    private void requireNonNull(E value) {
        if (value == null) {
            throw new NullPointerException("SkipListSet does not accept null values");
        }
    }
}
