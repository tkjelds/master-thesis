@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Node<T> {
        final T value;
        final int hash;
        Node<T> next;

        Node(T value, int hash, Node<T> next) {
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<E>[] buckets;
    private int size;
    private int threshold;

    public ConcurrentSet() {
        this(16);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAX_CAPACITY) {
            capacity <<= 1;
        }

        buckets = newTable(capacity);
        threshold = capacity - (capacity >>> 2);
    }

    public boolean add(E value) {
        synchronized (lock) {
            int hash = hash(value);
            int index = hash & (buckets.length - 1);

            for (Node<E> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && equal(value, node.value)) {
                    return false;
                }
            }

            if (size >= threshold && buckets.length < MAX_CAPACITY) {
                resize();
                index = hash & (buckets.length - 1);
            }

            buckets[index] = new Node<E>(value, hash, buckets[index]);
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        synchronized (lock) {
            int hash = hash(value);
            int index = hash & (buckets.length - 1);
            Node<E> previous = null;
            Node<E> node = buckets[index];

            while (node != null) {
                if (node.hash == hash && equal(value, node.value)) {
                    if (previous == null) {
                        buckets[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return true;
                }
                previous = node;
                node = node.next;
            }

            return false;
        }
    }

    public boolean contains(E value) {
        synchronized (lock) {
            int hash = hash(value);
            int index = hash & (buckets.length - 1);

            for (Node<E> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && equal(value, node.value)) {
                    return true;
                }
            }

            return false;
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
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            size = 0;
        }
    }

    private void resize() {
        int newCapacity = buckets.length << 1;
        if (newCapacity <= 0 || newCapacity > MAX_CAPACITY) {
            newCapacity = MAX_CAPACITY;
        }

        Node<E>[] newBuckets = newTable(newCapacity);
        for (Node<E> head : buckets) {
            Node<E> node = head;
            while (node != null) {
                Node<E> next = node.next;
                int index = node.hash & (newCapacity - 1);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private static int hash(Object value) {
        if (value == null) {
            return 0;
        }
        int hash = value.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    @SuppressWarnings("unchecked")
    private static <T> Node<T>[] newTable(int capacity) {
        return (Node<T>[]) new Node[capacity];
    }
}
