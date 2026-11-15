@SkipListSet.ThreadSafe
public class SkipListSet<T extends Comparable<? super T>> {
    @interface ThreadSafe {}


    private static final int DEFAULT_MAX_LEVELS = 16;
    private static final int UNBOUNDED = Integer.MAX_VALUE;

    private final Object monitor = new Object();
    private final int maxLevels;
    private final int maxCapacity;
    private final Node<T> head;
    private int size;

    public SkipListSet() {
        this(UNBOUNDED, DEFAULT_MAX_LEVELS);
    }

    public SkipListSet(int maxCapacity) {
        this(maxCapacity, DEFAULT_MAX_LEVELS);
    }

    public SkipListSet(int maxCapacity, int maxLevels) {
        if (maxLevels < 0) {
            throw new IllegalArgumentException("maxLevels must be >= 0");
        }
        if (maxCapacity < 0) {
            throw new IllegalArgumentException("maxCapacity must be >= 0");
        }
        this.maxCapacity = maxCapacity;
        this.maxLevels = maxLevels;
        this.head = new Node<T>(null, maxLevels);
        this.size = 0;
    }

    public boolean add(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            while (isCapacityBounded() && size >= maxCapacity) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to add", e);
                }
            }

            Node<T>[] update = findPredecessorsLocked(value);
            Node<T> current = update[0].next[0];
            if (current != null && current.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            Node<T> inserted = new Node<T>(value, level);
            for (int i = 0; i <= level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }

            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean offer(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            if (isCapacityBounded() && size >= maxCapacity) {
                return false;
            }
            Node<T>[] update = findPredecessorsLocked(value);
            Node<T> current = update[0].next[0];
            if (current != null && current.value.compareTo(value) == 0) {
                return false;
            }

            int level = randomLevel();
            Node<T> inserted = new Node<T>(value, level);
            for (int i = 0; i <= level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }

            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean remove(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            Node<T>[] update = findPredecessorsLocked(value);
            Node<T> target = update[0].next[0];
            if (target == null || target.value.compareTo(value) != 0) {
                return false;
            }

            removeLocked(target, update);
            monitor.notifyAll();
            return true;
        }
    }

    public T poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            Node<T> victim = head.next[0];
            T result = victim.value;
            Node<T>[] update = new Node[maxLevels + 1];
            Node<T> x = head;

            for (int level = maxLevels; level >= 0; level--) {
                while (x.next[level] != null && x.next[level] != victim
                        && x.next[level].value.compareTo(victim.value) < 0) {
                    x = x.next[level];
                }
                update[level] = x;
            }

            removeLocked(victim, update);
            monitor.notifyAll();
            return result;
        }
    }

    public T take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            Node<T> victim = head.next[0];
            T result = victim.value;
            Node<T>[] update = new Node[maxLevels + 1];
            Node<T> x = head;

            for (int level = maxLevels; level >= 0; level--) {
                while (x.next[level] != null && x.next[level] != victim
                        && x.next[level].value.compareTo(victim.value) < 0) {
                    x = x.next[level];
                }
                update[level] = x;
            }

            removeLocked(victim, update);
            monitor.notifyAll();
            return result;
        }
    }

    public T peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return head.next[0].value;
        }
    }

    public boolean contains(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            Node<T> current = head;
            for (int level = maxLevels; level >= 0; level--) {
                while (current.next[level] != null && current.next[level].value.compareTo(value) < 0) {
                    current = current.next[level];
                }
            }
            current = current.next[0];
            return current != null && current.value.compareTo(value) == 0;
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

    public boolean isFull() {
        synchronized (monitor) {
            return isCapacityBounded() && size >= maxCapacity;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i <= maxLevels; i++) {
                head.next[i] = null;
            }
            size = 0;
            monitor.notifyAll();
        }
    }

    private boolean isCapacityBounded() {
        return maxCapacity != UNBOUNDED;
    }

    private int randomLevel() {
        int level = 0;
        while (level < maxLevels && Math.random() < 0.5D) {
            level++;
        }
        return level;
    }

    @SuppressWarnings("unchecked")
    private Node<T>[] findPredecessorsLocked(T value) {
        Node<T>[] update = (Node<T>[]) new Node[maxLevels + 1];
        Node<T> current = head;

        for (int level = maxLevels; level >= 0; level--) {
            while (current.next[level] != null && current.next[level].value.compareTo(value) < 0) {
                current = current.next[level];
            }
            update[level] = current;
        }

        return update;
    }

    private void removeLocked(Node<T> target, Node<T>[] update) {
        for (int level = 0; level <= maxLevels; level++) {
            if (update[level].next[level] == target) {
                update[level].next[level] = target.next[level];
            }
        }
        size--;
    }

    private static final class Node<E> {
        private final E value;
        private final Node<E>[] next;

        @SuppressWarnings("unchecked")
        private Node(E value, int level) {
            this.value = value;
            this.next = (Node<E>[]) new Node[level + 1];
        }
    }
}
