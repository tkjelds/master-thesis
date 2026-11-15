@ConcurrentBoundedMap.ThreadSafe
public final class ConcurrentBoundedMap<K, V> {
    @interface ThreadSafe {}


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

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    // Single monitor guarding all mutable state below.
    private final Object lock = new Object();

    // Immutable after construction.
    private final int maxSize;

    // Guarded by lock. Bucket count is always a power of two.
    private Node<K, V>[] buckets;
    private int size;

    /**
     * Creates a map that holds at most {@code maxSize} entries.
     *
     * Blocking policy:
     *  - put(...) blocks while the map is full and the key is not already present,
     *    until a remove/clear frees space.
     *  - awaitValue(...) blocks until the key is present.
     *  - get, containsKey, remove, tryPut, size, clear never block.
     */
    @SuppressWarnings("unchecked")
    public ConcurrentBoundedMap(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        int capacity = DEFAULT_INITIAL_CAPACITY;
        this.buckets = (Node<K, V>[]) new Node[capacity];
        this.size = 0;
    }

    /**
     * Inserts or updates a mapping. Blocks while the map is full and the key is absent.
     * Returns the previous value, or null if there was none.
     */
    public V put(K key, V value) throws InterruptedException {
        checkKeyAndValue(key, value);
        synchronized (lock) {
            while (true) {
                Node<K, V> node = findNode(key);
                if (node != null) {
                    V old = node.value;
                    node.value = value;
                    return old;
                }
                if (size < maxSize) {
                    insertNew(key, value);
                    lock.notifyAll();
                    return null;
                }
                lock.wait();
            }
        }
    }

    /**
     * Non-blocking insert or update. Returns false only if the map is full and the key is absent.
     */
    public boolean tryPut(K key, V value) {
        checkKeyAndValue(key, value);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            if (node != null) {
                node.value = value;
                return true;
            }
            if (size < maxSize) {
                insertNew(key, value);
                lock.notifyAll();
                return true;
            }
            return false;
        }
    }

    /**
     * Returns the value for the key, or null if absent. Never blocks.
     */
    public V get(K key) {
        checkKey(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    /**
     * Blocks until the key is present, then returns its value without removing it.
     */
    public V awaitValue(K key) throws InterruptedException {
        checkKey(key);
        synchronized (lock) {
            while (true) {
                Node<K, V> node = findNode(key);
                if (node != null) {
                    return node.value;
                }
                lock.wait();
            }
        }
    }

    /**
     * Returns true if the key is present. Never blocks.
     */
    public boolean containsKey(K key) {
        checkKey(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    /**
     * Removes the mapping for the key and returns its previous value, or null if absent.
     * Wakes blocked writers and readers waiting on this map.
     */
    public V remove(K key) {
        checkKey(key);
        synchronized (lock) {
            int idx = indexFor(hash(key), buckets.length);
            Node<K, V> prev = null;
            Node<K, V> cur = buckets[idx];
            while (cur != null) {
                if (cur.key.equals(key)) {
                    if (prev == null) {
                        buckets[idx] = cur.next;
                    } else {
                        prev.next = cur.next;
                    }
                    size--;
                    lock.notifyAll();
                    return cur.value;
                }
                prev = cur;
                cur = cur.next;
            }
            return null;
        }
    }

    /**
     * Returns the current number of entries.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Removes all entries and wakes blocked writers.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ---- Private helpers: all must be called while holding lock. ----

    private Node<K, V> findNode(K key) {
        int h = hash(key);
        Node<K, V> cur = buckets[indexFor(h, buckets.length)];
        while (cur != null) {
            if (cur.key.equals(key)) {
                return cur;
            }
            cur = cur.next;
        }
        return null;
    }

    private void insertNew(K key, V value) {
        int idx = indexFor(hash(key), buckets.length);
        buckets[idx] = new Node<K, V>(key, value, buckets[idx]);
        size++;
        if (size > buckets.length - (buckets.length >>> 2) && buckets.length < MAXIMUM_CAPACITY) {
            resize();
        }
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Node<K, V>[] oldBuckets = buckets;
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[oldBuckets.length << 1];
        for (int i = 0; i < oldBuckets.length; i++) {
            Node<K, V> cur = oldBuckets[i];
            while (cur != null) {
                Node<K, V> next = cur.next;
                int idx = indexFor(hash(cur.key), newBuckets.length);
                cur.next = newBuckets[idx];
                newBuckets[idx] = cur;
                cur = next;
            }
        }
        buckets = newBuckets;
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    private static void checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
    }

    private static void checkKeyAndValue(Object key, Object value) {
        checkKey(key);
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
    }
}
