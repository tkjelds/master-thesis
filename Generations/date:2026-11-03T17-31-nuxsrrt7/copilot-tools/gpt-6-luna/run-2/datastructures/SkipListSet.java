@SkipListSet.ThreadSafe
public final class SkipListSet<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private final Object mutex = new Object();
    private final Node<E> head = new Node<>(null, MAX_LEVEL);
    private int currentLevel = 1;
    private int size;
    private int randomState = 0x6D2B79F5;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[level];
        }
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] newUpdateArray() {
        return (Node<E>[]) new Node<?>[MAX_LEVEL];
    }

    private void checkValue(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL) {
            randomState ^= randomState << 13;
            randomState ^= randomState >>> 17;
            randomState ^= randomState << 5;
            if ((randomState & 1) == 0) {
                break;
            }
            level++;
        }
        return level;
    }

    public boolean add(E value) {
        checkValue(value);
        synchronized (mutex) {
            Node<E>[] update = newUpdateArray();
            Node<E> current = head;

            for (int i = currentLevel - 1; i >= 0; i--) {
                Node<E> next = current.next[i];
                while (next != null && next.value.compareTo(value) < 0) {
                    current = next;
                    next = current.next[i];
                }
                update[i] = current;
            }

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

            Node<E> inserted = new Node<>(value, level);
            for (int i = 0; i < level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }
            size++;
            return true;
        }
    }

    public boolean contains(E value) {
        checkValue(value);
        synchronized (mutex) {
            Node<E> current = head;
            for (int i = currentLevel - 1; i >= 0; i--) {
                Node<E> next = current.next[i];
                while (next != null && next.value.compareTo(value) < 0) {
                    current = next;
                    next = current.next[i];
                }
            }
            Node<E> candidate = current.next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
        }
    }

    public boolean remove(E value) {
        checkValue(value);
        synchronized (mutex) {
            Node<E>[] update = newUpdateArray();
            Node<E> current = head;

            for (int i = currentLevel - 1; i >= 0; i--) {
                Node<E> next = current.next[i];
                while (next != null && next.value.compareTo(value) < 0) {
                    current = next;
                    next = current.next[i];
                }
                update[i] = current;
            }

            Node<E> candidate = update[0].next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }

            for (int i = 0; i < currentLevel; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            size--;
            return true;
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    public E first() {
        synchronized (mutex) {
            Node<E> first = head.next[0];
            if (first == null) {
                throw new IllegalStateException("set is empty");
            }
            return first.value;
        }
    }

    public E pollFirst() {
        synchronized (mutex) {
            Node<E> first = head.next[0];
            if (first == null) {
                return null;
            }

            for (int i = 0; i < first.next.length; i++) {
                head.next[i] = first.next[i];
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            size--;
            return first.value;
        }
    }

    public Object[] toArray() {
        synchronized (mutex) {
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
}
