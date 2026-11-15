@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> next;

        Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Object[] buckets;
    private int size;
    private int threshold;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        buckets = new Object[capacity];
        threshold = capacity - capacity / 4;
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (this) {
            int hash = spread(key.hashCode());
            int index = hash & (buckets.length - 1);

            for (Node<K, V> node = nodeAt(index); node != null; node = node.next) {
                if (node.key == key || node.key.equals(key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            buckets[index] = new Node<K, V>(key, value, nodeAt(index));
            size++;
            if (size > threshold && buckets.length < MAXIMUM_CAPACITY) {
                resize();
            }
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            int index = spread(key.hashCode()) & (buckets.length - 1);
            for (Node<K, V> node = nodeAt(index); node != null; node = node.next) {
                if (node.key == key || node.key.equals(key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            int index = spread(key.hashCode()) & (buckets.length - 1);
            for (Node<K, V> node = nodeAt(index); node != null; node = node.next) {
                if (node.key == key || node.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            int index = spread(key.hashCode()) & (buckets.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = nodeAt(index);

            while (node != null) {
                if (node.key == key || node.key.equals(key)) {
                    if (previous == null) {
                        buckets[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return node.value;
                }
                previous = node;
                node = node.next;
            }
            return null;
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
            buckets = new Object[DEFAULT_CAPACITY];
            threshold = DEFAULT_CAPACITY - DEFAULT_CAPACITY / 4;
            size = 0;
        }
    }

    private void resize() {
        int newCapacity = buckets.length << 1;
        if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        }

        Object[] newBuckets = new Object[newCapacity];
        for (int i = 0; i < buckets.length; i++) {
            Node<K, V> node = nodeAt(i);
            while (node != null) {
                Node<K, V> next = node.next;
                int index = spread(node.key.hashCode()) & (newCapacity - 1);
                node.next = nodeAt(newBuckets, index);
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = newCapacity - newCapacity / 4;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> nodeAt(int index) {
        return (Node<K, V>) buckets[index];
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> nodeAt(Object[] array, int index) {
        return (Node<K, V>) array[index];
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }
}
