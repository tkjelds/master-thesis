@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

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

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity < 1 || initialCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        buckets = (Node<K, V>[]) new Node[capacity];
        threshold = capacity - (capacity >>> 2);
    }

    public V get(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (lock) {
            Node<K, V> node = findNode(key, hash);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (lock) {
            return findNode(key, hash) != null;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
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

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary is full");
            }
            if (size + 1 > threshold && buckets.length < MAX_CAPACITY) {
                resize();
                index = hash & (buckets.length - 1);
            }

            buckets[index] = new Node<K, V>(hash, key, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
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

    private Node<K, V> findNode(K key, int hash) {
        int index = hash & (buckets.length - 1);
        for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
            if (node.hash == hash && node.key.equals(key)) {
                return node;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int newCapacity = buckets.length << 1;
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[newCapacity];

        for (Node<K, V> head : buckets) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newCapacity - 1);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private static int hash(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Null keys are not supported");
        }
    }
}
