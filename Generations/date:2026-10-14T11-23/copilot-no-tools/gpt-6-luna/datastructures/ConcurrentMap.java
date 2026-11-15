public final class ConcurrentMap<K, V> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

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
    private int threshold;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < (1 << 30)) {
            capacity <<= 1;
        }

        buckets = (Node<K, V>[]) new Node[capacity];
        threshold = thresholdFor(capacity);
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        int hash = hash(key);
        synchronized (lock) {
            int index = hash & (buckets.length - 1);
            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && node.key.equals(key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            buckets[index] = new Node<K, V>(hash, key, value, buckets[index]);
            size++;
            if (size > threshold && buckets.length < (1 << 30)) {
                resize();
            }
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        int hash = hash(key);
        synchronized (lock) {
            int index = hash & (buckets.length - 1);
            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && node.key.equals(key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        int hash = hash(key);
        synchronized (lock) {
            int index = hash & (buckets.length - 1);
            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && node.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        int hash = hash(key);
        synchronized (lock) {
            int index = hash & (buckets.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = buckets[index];

            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
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
        @SuppressWarnings("unchecked")
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[buckets.length << 1];

        for (Node<K, V> bucket : buckets) {
            Node<K, V> node = bucket;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newBuckets.length - 1);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = thresholdFor(newBuckets.length);
    }

    private static int hash(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int thresholdFor(int capacity) {
        long threshold = (long) (capacity * LOAD_FACTOR);
        return threshold > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) threshold;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }
}
