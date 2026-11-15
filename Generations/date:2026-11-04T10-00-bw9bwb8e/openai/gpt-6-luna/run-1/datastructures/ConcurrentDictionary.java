@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final int DEFAULT_CAPACITY = 16;

    private static final class Node<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<K, V>[] buckets;
    private int size;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0 || initialCapacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("Invalid capacity");
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        buckets = newBuckets(capacity);
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (buckets.length - 1);

            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            int threshold = buckets.length - (buckets.length >>> 2);
            if (size >= threshold && buckets.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (buckets.length - 1);
            }

            buckets[index] = new Node<K, V>(hash, key, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (buckets.length - 1);

            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (buckets.length - 1);

            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (buckets.length - 1);
            Node<K, V> previous = null;
            Node<K, V> current = buckets[index];

            while (current != null) {
                if (current.hash == hash && key.equals(current.key)) {
                    if (previous == null) {
                        buckets[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    return current.value;
                }
                previous = current;
                current = current.next;
            }
            return null;
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
        Node<K, V>[] oldBuckets = buckets;
        Node<K, V>[] newBuckets = newBuckets(oldBuckets.length << 1);
        int mask = newBuckets.length - 1;

        for (Node<K, V> head : oldBuckets) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & mask;
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBuckets(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
