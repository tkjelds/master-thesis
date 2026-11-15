/**
 * A thread-safe set ordered by the natural ordering of its elements.
 * Elements must not be null and their ordering must remain stable while stored.
 *
 * Operations do not wait for elements or capacity. first() and pollFirst()
 * return null when empty. Adding a new element at the maximum size throws
 * IllegalStateException; adding an existing element still returns false.
 */
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
    private int level = 1;
    private int size;
    private int randomState = 0x6d2b79f5;

    private int randomLevel() {
        int height = 1;
        while (height < MAX_LEVEL) {
            randomState ^= randomState << 13;
            randomState ^= randomState >>> 17;
            randomState ^= randomState << 5;
            if ((randomState & 1) == 0) {
                break;
            }
            height++;
        }
        return height;
    }

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] predecessors = (Node<E>[]) new Node<?>[MAX_LEVEL];
            Node<E> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].value.compareTo(value) < 0) {
                    current = current.next[i];
                }
                predecessors[i] = current;
            }
            Node<E> candidate = current.next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("set is full");
            }
            int height = randomLevel();
            Node<E> inserted = new Node<E>(value, height);
            for (int i = level; i < height; i++) {
                predecessors[i] = head;
            }
            for (int i = 0; i < height; i++) {
                inserted.next[i] = predecessors[i].next[i];
                predecessors[i].next[i] = inserted;
            }
            if (height > level) {
                level = height;
            }
            size++;
            return true;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (lock) {
            Node<E> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].value.compareTo(value) < 0) {
                    current = current.next[i];
                }
            }
            Node<E> candidate = current.next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<E>[] predecessors = (Node<E>[]) new Node<?>[MAX_LEVEL];
            Node<E> current = head;
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].value.compareTo(value) < 0) {
                    current = current.next[i];
                }
                predecessors[i] = current;
            }
            Node<E> candidate = current.next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }
            for (int i = 0; i < candidate.next.length; i++) {
                predecessors[i].next[i] = candidate.next[i];
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return true;
        }
    }

    public E first() {
        synchronized (lock) {
            return head.next[0] == null ? null : head.next[0].value;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            Node<E> first = head.next[0];
            if (first == null) {
                return null;
            }
            for (int i = 0; i < first.next.length; i++) {
                head.next[i] = first.next[i];
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            return first.value;
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
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
            size = 0;
        }
    }
}
