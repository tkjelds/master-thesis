@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int MAX_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Node<K, V>[] buckets;
    private int size;

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

    public ConcurrentMap() {
        this(16);
    }

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAX_CAPACITY) {
            capacity <<= 1;
        }

        buckets = newBucketArray(capacity);
    }

    public V get(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (lock) {
            Node<K, V> node = buckets[index(hash, buckets.length)];
            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    return node.value;
                }
                node = node.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (lock) {
            Node<K, V> node = buckets[index(hash, buckets.length)];
            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        int hash = hash(key);

        synchronized (lock) {
            int bucketIndex = index(hash, buckets.length);
            Node<K, V> node = buckets[bucketIndex];

            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    V previousValue = node.value;
                    node.value = value;
                    return previousValue;
                }
                node = node.next;
            }

            if (size >= buckets.length - (buckets.length >>> 2)
                    && buckets.length < MAX_CAPACITY) {
                resize();
                bucketIndex = index(hash, buckets.length);
            }

            buckets[bucketIndex] =
                    new Node<K, V>(hash, key, value, buckets[bucketIndex]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (lock) {
            int bucketIndex = index(hash, buckets.length);
            Node<K, V> previous = null;
            Node<K, V> node = buckets[bucketIndex];

            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    if (previous == null) {
                        buckets[bucketIndex] = node.next;
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
            buckets = newBucketArray(buckets.length);
            size = 0;
        }
    }

    private void resize() {
        Node<K, V>[] oldBuckets = buckets;
        Node<K, V>[] newBuckets = newBucketArray(oldBuckets.length << 1);

        for (Node<K, V> head : oldBuckets) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int bucketIndex = index(node.hash, newBuckets.length);
                node.next = newBuckets[bucketIndex];
                newBuckets[bucketIndex] = node;
                node = next;
            }
        }

        buckets = newBuckets;
    }

    private static int hash(Object key) {
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static int index(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newBucketArray(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
