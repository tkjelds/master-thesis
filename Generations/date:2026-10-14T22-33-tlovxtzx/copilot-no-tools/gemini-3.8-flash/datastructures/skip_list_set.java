@skip_list_set.ThreadSafe
public class skip_list_set<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final int DEFAULT_MAX_LEVEL = 32;

    private final int capacity;
    private final int maxLevel;
    private final Node<E> head;

    private volatile int currentLevel;
    private volatile int size;
    private volatile long randomState;

    private static final class Node<T> {
        private final T value;
        private final Node<T>[] next;

        @SuppressWarnings("unchecked")
        private Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node<?>[level];
        }
    }

    public skip_list_set() {
        this(Integer.MAX_VALUE, DEFAULT_MAX_LEVEL);
    }

    public skip_list_set(int capacity) {
        this(capacity, DEFAULT_MAX_LEVEL);
    }

    @SuppressWarnings("unchecked")
    public skip_list_set(int capacity, int maxLevel) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel must be positive");
        }
        this.capacity = capacity;
        this.maxLevel = maxLevel;
        this.head = new Node<E>(null, maxLevel);
        this.currentLevel = 1;
        this.size = 0;
        this.randomState = initialRandomState();
    }

    public boolean add(E value) {
        synchronized (this) {
            if (value == null) {
                throw new NullPointerException("Null elements are not supported");
            }
            Node<E>[] predecessors = findPredecessors(value);
            Node<E> candidate = predecessors[0].next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }
            if (size >= capacity) {
                return false;
            }
            insertAt(value, predecessors);
            return true;
        }
    }

    public void put(E value) throws InterruptedException {
        synchronized (this) {
            if (value == null) {
                throw new NullPointerException("Null elements are not supported");
            }
            while (true) {
                Node<E>[] predecessors = findPredecessors(value);
                Node<E> candidate = predecessors[0].next[0];
                if (candidate != null && candidate.value.compareTo(value) == 0) {
                    return;
                }
                if (size < capacity) {
                    insertAt(value, predecessors);
                    return;
                }
                wait();
            }
        }
    }

    public boolean addWait(E value) throws InterruptedException {
        synchronized (this) {
            if (value == null) {
                throw new NullPointerException("Null elements are not supported");
            }
            while (true) {
                Node<E>[] predecessors = findPredecessors(value);
                Node<E> candidate = predecessors[0].next[0];
                if (candidate != null && candidate.value.compareTo(value) == 0) {
                    return false;
                }
                if (size < capacity) {
                    insertAt(value, predecessors);
                    return true;
                }
                wait();
            }
        }
    }

    public boolean remove(E value) {
        synchronized (this) {
            if (value == null) {
                return false;
            }
            Node<E>[] predecessors = findPredecessors(value);
            Node<E> candidate = predecessors[0].next[0];
            if (candidate == null || candidate.value.compareTo(value) != 0) {
                return false;
            }
            for (int i = 0; i < candidate.next.length; i++) {
                if (predecessors[i].next[i] == candidate) {
                    predecessors[i].next[i] = candidate.next[i];
                }
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            size--;
            notifyAll();
            return true;
        }
    }

    public boolean contains(E value) {
        synchronized (this) {
            if (value == null) {
                return false;
            }
            Node<E> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
            }
            current = current.next[0];
            return current != null && current.value.compareTo(value) == 0;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node<E> first = head.next[0];
            for (int i = 0; i < first.next.length; i++) {
                head.next[i] = first.next[i];
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            size--;
            notifyAll();
            return first.value;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            Node<E> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }
            E val = current.value;
            remove(val);
            return val;
        }
    }

    public E pollFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            Node<E> first = head.next[0];
            for (int i = 0; i < first.next.length; i++) {
                head.next[i] = first.next[i];
            }
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            size--;
            notifyAll();
            return first.value;
        }
    }

    public E pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            Node<E> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }
            E val = current.value;
            remove(val);
            return val;
        }
    }

    public E first() {
        synchronized (this) {
            Node<E> first = head.next[0];
            return first == null ? null : first.value;
        }
    }

    public E last() {
        synchronized (this) {
            Node<E> current = head;
            for (int level = currentLevel - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }
            return current == head ? null : current.value;
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

    public boolean isFull() {
        synchronized (this) {
            return size >= capacity;
        }
    }

    public int capacity() {
        synchronized (this) {
            return capacity;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < maxLevel; i++) {
                head.next[i] = null;
            }
            currentLevel = 1;
            size = 0;
            notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (this) {
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

    public void awaitNonEmpty() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
        }
    }

    public void awaitEmpty() throws InterruptedException {
        synchronized (this) {
            while (size > 0) {
                wait();
            }
        }
    }

    public void awaitNonFull() throws InterruptedException {
        synchronized (this) {
            while (size >= capacity) {
                wait();
            }
        }
    }

    private void insertAt(E value, Node<E>[] predecessors) {
        int level = randomLevel();
        if (level > currentLevel) {
            for (int i = currentLevel; i < level; i++) {
                predecessors[i] = head;
            }
            currentLevel = level;
        }

        Node<E> newNode = new Node<E>(value, level);
        for (int i = 0; i < level; i++) {
            newNode.next[i] = predecessors[i].next[i];
            predecessors[i].next[i] = newNode;
        }

        size++;
        notifyAll();
    }

    @SuppressWarnings("unchecked")
    private Node<E>[] findPredecessors(E value) {
        Node<E>[] predecessors = (Node<E>[]) new Node<?>[maxLevel];
        Node<E> current = head;
        for (int level = currentLevel - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].value.compareTo(value) < 0) {
                current = current.next[level];
            }
            predecessors[level] = current;
        }
        return predecessors;
    }

    private int randomLevel() {
        int level = 1;
        long state = randomState;
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        randomState = state;

        long s = state;
        while (level < maxLevel && (s & 1L) == 0L) {
            level++;
            s >>>= 1;
        }
        return level;
    }

    private static long initialRandomState() {
        long value = System.nanoTime();
        return value == 0L ? 0x2545F4914F6CDD1DL : value;
    }
}
