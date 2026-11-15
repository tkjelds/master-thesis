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
        while (capacity < initialCapacity && capacity < MAX_CAPACITY) {
            capacity <<= 1;
        }
        buckets = new Object[capacity];
        threshold = capacity - (capacity >>> 2);
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            Node<K, V> node = bucketAt(index);

            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
                node = node.next;
            }

            buckets[index] = new Node<K, V>(hash, key, value, bucketAt(index));
            size++;
            if (size > threshold && buckets.length < MAX_CAPACITY) {
                resize();
            }
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = hash(key);
            Node<K, V> node = bucketAt(indexFor(hash, buckets.length));
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

        synchronized (lock) {
            int hash = hash(key);
            Node<K, V> node = bucketAt(indexFor(hash, buckets.length));
            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            Node<K, V> node = bucketAt(index);
            Node<K, V> previous = null;

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
        Object[] oldBuckets = buckets;
        Object[] newBuckets = new Object[oldBuckets.length << 1];

        for (int i = 0; i < oldBuckets.length; i++) {
            Node<K, V> node = bucketAt(oldBuckets, i);
            while (node != null) {
                Node<K, V> next = node.next;
                int index = indexFor(node.hash, newBuckets.length);
                node.next = bucketAt(newBuckets, index);
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = newBuckets.length - (newBuckets.length >>> 2);
    }

    private static int hash(Object key) {
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> bucketAt(int index) {
        return (Node<K, V>) buckets[index];
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V> bucketAt(Object[] array, int index) {
        return (Node<K, V>) array[index];
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }
}
