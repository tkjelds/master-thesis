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

    private final Object lock = new Object();
    private final Node<E> head = new Node<>(null, MAX_LEVEL);
    private int currentLevel = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("SkipListSet does not allow null values");
        }

        synchronized (lock) {
            Node<E>[] update = newNodeArray();
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
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

            int nodeLevel = randomLevel();
            if (nodeLevel > currentLevel) {
                for (int level = currentLevel; level < nodeLevel; level++) {
                    update[level] = head;
                }
                currentLevel = nodeLevel;
            }

            Node<E> node = new Node<>(value, nodeLevel);
            for (int level = 0; level < nodeLevel; level++) {
                node.next[level] = update[level].next[level];
                update[level].next[level] = node;
            }

            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("SkipListSet does not allow null values");
        }

        synchronized (lock) {
            Node<E>[] update = newNodeArray();
            Node<E> current = head;

            for (int level = currentLevel - 1; level >= 0; level--) {
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
            throw new NullPointerException("SkipListSet does not allow null values");
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
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    public E first() {
        synchronized (lock) {
            if (head.next[0] == null) {
                throw new IllegalStateException("SkipListSet is empty");
            }
            return head.next[0].value;
        }
    }

    public E last() {
        synchronized (lock) {
            Node<E> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }

            if (current == head) {
                throw new IllegalStateException("SkipListSet is empty");
            }
            return current.value;
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

    @SuppressWarnings("unchecked")
    private Node<E>[] newNodeArray() {
        return (Node<E>[]) new Node<?>[MAX_LEVEL];
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && (nextRandom() & 1L) == 0L) {
            level++;
        }
        return level;
    }

    private long nextRandom() {
        randomState ^= randomState << 13;
        randomState ^= randomState >>> 7;
        randomState ^= randomState << 17;
        return randomState;
    }
}
