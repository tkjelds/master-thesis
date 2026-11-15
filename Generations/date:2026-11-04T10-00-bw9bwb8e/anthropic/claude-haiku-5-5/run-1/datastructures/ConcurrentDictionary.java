@ConcurrentDictionary.ThreadSafe
@SuppressWarnings("unchecked")
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}


    private static final class Node<K, V> {
        final K key;
        final int hash;
        V value;
        Node<K, V> next;

        Node(K key, int hash, V value, Node<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    // Single intrinsic monitor guarding all mutable state below.
    private final Object lock = new Object();

    // Immutable configuration (final fields are safely published).
    private final int capacity;
    private final Node<K, V>[] buckets;

    // Guarded by lock.
    private int size;

    /**
     * Creates a bounded dictionary holding at most {@code capacity} entries.
     *
     * Blocking policy:
     *  - put(key, value) blocks while the dictionary is full and the key is absent.
     *  - awaitValue(key) blocks while the key is absent.
     *  - tryPut(key, value) never blocks; it returns false if the dictionary is full
     *    and the key is absent.
     *  - get(key) and remove(key) never block; they return null when the key is absent.
     *
     * Null keys and null values are rejected with NullPointerException.
     */
    public ConcurrentDictionary(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buckets = (Node<K, V>[]) new Node[Math.max(8, capacity * 2)];
    }

    private static int hashOf(Object key) {
        return key.hashCode();
    }

    private int indexFor(int hash) {
        return (hash & 0x7fffffff) % buckets.length;
    }

    // Must be called while holding lock.
    private Node<K, V> findNode(Object key, int hash) {
        Node<K, V> n = buckets[indexFor(hash)];
        while (n != null) {
            if (n.hash == hash && n.key.equals(key)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    /**
     * Inserts or updates a mapping. If the dictionary is full and the key is new,
     * blocks until space becomes available.
     *
     * @return the previous value associated with key, or null if none
     */
    public V put(K key, V value) throws InterruptedException {
        if (key == null || value == null) {
            throw new NullPointerException("key and value must not be null");
        }
        final int hash = hashOf(key);
        synchronized (lock) {
            while (true) {
                Node<K, V> existing = findNode(key, hash);
                if (existing != null) {
                    V old = existing.value;
                    existing.value = value;
                    return old;
                }
                if (size < capacity) {
                    int idx = indexFor(hash);
                    buckets[idx] = new Node<>(key, hash, value, buckets[idx]);
                    size++;
                    lock.notifyAll();
                    return null;
                }
                lock.wait();
            }
        }
    }

    /**
     * Non-blocking insert or update.
     *
     * @return true if the mapping was stored or updated; false if the dictionary
     *         is full and the key is absent
     */
    public boolean tryPut(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key and value must not be null");
        }
        final int hash = hashOf(key);
        synchronized (lock) {
            Node<K, V> existing = findNode(key, hash);
            if (existing != null) {
                existing.value = value;
                return true;
            }
            if (size < capacity) {
                int idx = indexFor(hash);
                buckets[idx] = new Node<>(key, hash, value, buckets[idx]);
                size++;
                lock.notifyAll();
                return true;
            }
            return false;
        }
    }

    /**
     * Non-blocking lookup.
     *
     * @return the value associated with key, or null if absent
     */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        final int hash = hashOf(key);
        synchronized (lock) {
            Node<K, V> n = findNode(key, hash);
            return n == null ? null : n.value;
        }
    }

    /**
     * Blocks until the key is present, then returns its value.
     */
    public V awaitValue(K key) throws InterruptedException {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        final int hash = hashOf(key);
        synchronized (lock) {
            Node<K, V> n = findNode(key, hash);
            while (n == null) {
                lock.wait();
                n = findNode(key, hash);
            }
            return n.value;
        }
    }

    /**
     * Non-blocking removal.
     *
     * @return the removed value, or null if the key was absent
     */
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        final int hash = hashOf(key);
        synchronized (lock) {
            int idx = indexFor(hash);
            Node<K, V> prev = null;
            Node<K, V> n = buckets[idx];
            while (n != null) {
                if (n.hash == hash && n.key.equals(key)) {
                    if (prev == null) {
                        buckets[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    size--;
                    lock.notifyAll();
                    return n.value;
                }
                prev = n;
                n = n.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        final int hash = hashOf(key);
        synchronized (lock) {
            return findNode(key, hash) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }
}
