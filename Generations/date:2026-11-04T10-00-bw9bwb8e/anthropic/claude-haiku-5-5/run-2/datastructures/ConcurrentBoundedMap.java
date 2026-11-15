@ConcurrentBoundedMap.ThreadSafe
public final class ConcurrentBoundedMap<K, V> {
    @interface ThreadSafe {}


    /*
     * Blocking policy:
     *  - put(key, value) blocks while the map is full AND the key is not already present.
     *    If the key already exists, its value is replaced without blocking.
     *  - tryPut(key, value) never blocks; it returns false if the map is full and the key is absent.
     *  - awaitValue(key) blocks until the key is present, then returns its value.
     *  - get, containsKey, remove, size, isEmpty, clear never block.
     *  - All waits are guarded by while loops and are woken with notifyAll on every
     *    state change that could unblock a waiter (insertion, removal, clear).
     *  - Null keys and null values are rejected with NullPointerException.
     *  - Interruption during a blocking call propagates as InterruptedException.
     *
     * Concurrency:
     *  - All mutable state (buckets, size) is guarded by a single private lock object.
     *  - All accesses to shared state occur inside synchronized(lock) blocks, which
     *    provides mutual exclusion, visibility, and happens-before ordering.
     *  - The bucket array reference is final and assigned in the constructor, so it is
     *    safely published to all threads that obtain the map reference.
     */

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

    private static final int MAX_CAPACITY = 1 << 29;

    private final Object lock = new Object();
    private final int capacity;
    private final Node<K, V>[] buckets;
    private int size;

    @SuppressWarnings("unchecked")
    public ConcurrentBoundedMap(int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1, " + MAX_CAPACITY + "]");
        }
        this.capacity = capacity;
        int n = 1;
        while (n < capacity * 2) {
            n <<= 1;
        }
        this.buckets = (Node<K, V>[]) new Node[n];
        this.size = 0;
    }

    public int capacity() {
        return capacity;
    }

    public V put(K key, V value) throws InterruptedException {
        checkNotNull(key);
        checkNotNull(value);
        int h = spread(key.hashCode());
        synchronized (lock) {
            Node<K, V> existing = findNode(key, h);
            if (existing != null) {
                V old = existing.value;
                existing.value = value;
                return old;
            }
            while (size == capacity) {
                lock.wait();
                existing = findNode(key, h);
                if (existing != null) {
                    V old = existing.value;
                    existing.value = value;
                    return old;
                }
            }
            insertNew(key, h, value);
            lock.notifyAll();
            return null;
        }
    }

    public boolean tryPut(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int h = spread(key.hashCode());
        synchronized (lock) {
            Node<K, V> existing = findNode(key, h);
            if (existing != null) {
                existing.value = value;
                return true;
            }
            if (size == capacity) {
                return false;
            }
            insertNew(key, h, value);
            lock.notifyAll();
            return true;
        }
    }

    public V get(K key) {
        checkNotNull(key);
        int h = spread(key.hashCode());
        synchronized (lock) {
            Node<K, V> n = findNode(key, h);
            return n == null ? null : n.value;
        }
    }

    public boolean containsKey(K key) {
        checkNotNull(key);
        int h = spread(key.hashCode());
        synchronized (lock) {
            return findNode(key, h) != null;
        }
    }

    public V remove(K key) {
        checkNotNull(key);
        int h = spread(key.hashCode());
        synchronized (lock) {
            int idx = h & (buckets.length - 1);
            Node<K, V> prev = null;
            Node<K, V> cur = buckets[idx];
            while (cur != null) {
                if (cur.hash == h && (cur.key == key || cur.key.equals(key))) {
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
     * Blocks until the given key is present in the map, then returns its value.
     */
    public V awaitValue(K key) throws InterruptedException {
        checkNotNull(key);
        int h = spread(key.hashCode());
        synchronized (lock) {
            Node<K, V> n = findNode(key, h);
            while (n == null) {
                lock.wait();
                n = findNode(key, h);
            }
            return n.value;
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
            lock.notifyAll();
        }
    }

    // ---- helpers: must only be called while holding lock ----

    private Node<K, V> findNode(K key, int h) {
        Node<K, V> cur = buckets[h & (buckets.length - 1)];
        while (cur != null) {
            if (cur.hash == h && (cur.key == key || cur.key.equals(key))) {
                return cur;
            }
            cur = cur.next;
        }
        return null;
    }

    private void insertNew(K key, int h, V value) {
        int idx = h & (buckets.length - 1);
        buckets[idx] = new Node<>(h, key, value, buckets[idx]);
        size++;
    }

    // ---- static helpers ----

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }
}
