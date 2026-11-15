@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}


    private static final int DEFAULT_CAPACITY = 16;

    private static final class Node<K, V> {
        private final K key;
        private V value;
        private Node<K, V> next;

        private Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    // All mutable state below is guarded by 'lock'.
    private final Object lock = new Object();
    private Node<K, V>[] buckets;
    private int size;

    /**
     * Blocking policy:
     *  - put, get, containsKey, remove, size, isEmpty never block on capacity; the
     *    dictionary grows as needed, so they complete immediately after acquiring the lock.
     *  - awaitKey(key) blocks (via wait) until the key is present, then returns its value.
     *    It is re-checked in a while loop after every notifyAll, and it throws
     *    InterruptedException if the waiting thread is interrupted.
     *  - Null keys and null values are rejected with NullPointerException.
     */
    @SuppressWarnings("unchecked")
    public ConcurrentDictionary() {
        this.buckets = (Node<K, V>[]) new Node[DEFAULT_CAPACITY];
        this.size = 0;
    }

    /**
     * Inserts or replaces the mapping for key.
     * @return the previous value, or null if the key was absent.
     */
    public V put(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        synchronized (lock) {
            int idx = indexFor(hash(key), buckets.length);
            for (Node<K, V> n = buckets[idx]; n != null; n = n.next) {
                if (key.equals(n.key)) {
                    V old = n.value;
                    n.value = value;
                    return old;
                }
            }
            buckets[idx] = new Node<K, V>(key, value, buckets[idx]);
            size++;
            if (size > buckets.length * 3 / 4) {
                resize(buckets.length * 2);
            }
            // A new key was added: wake threads waiting in awaitKey.
            lock.notifyAll();
            return null;
        }
    }

    /**
     * @return the value mapped to key, or null if absent.
     */
    public V get(K key) {
        checkNotNull(key);
        synchronized (lock) {
            Node<K, V> n = findNode(key);
            return (n == null) ? null : n.value;
        }
    }

    public boolean containsKey(K key) {
        checkNotNull(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    /**
     * Removes the mapping for key.
     * @return the removed value, or null if the key was absent.
     */
    public V remove(K key) {
        checkNotNull(key);
        synchronized (lock) {
            int idx = indexFor(hash(key), buckets.length);
            Node<K, V> prev = null;
            for (Node<K, V> n = buckets[idx]; n != null; prev = n, n = n.next) {
                if (key.equals(n.key)) {
                    if (prev == null) {
                        buckets[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    n.next = null;
                    size--;
                    return n.value;
                }
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

    /**
     * Blocks until key is present in the dictionary, then returns its current value.
     * Waits indefinitely; the caller can stop waiting by interrupting the thread,
     * which causes an InterruptedException.
     */
    public V awaitKey(K key) throws InterruptedException {
        checkNotNull(key);
        synchronized (lock) {
            while (true) {
                Node<K, V> n = findNode(key);
                if (n != null) {
                    return n.value;
                }
                lock.wait();
            }
        }
    }

    /**
     * Removes all mappings.
     */
    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (lock) {
            buckets = (Node<K, V>[]) new Node[DEFAULT_CAPACITY];
            size = 0;
        }
    }

    // ---- Private helpers: caller must hold 'lock' ----

    private Node<K, V> findNode(K key) {
        int idx = indexFor(hash(key), buckets.length);
        for (Node<K, V> n = buckets[idx]; n != null; n = n.next) {
            if (key.equals(n.key)) {
                return n;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize(int newCapacity) {
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[newCapacity];
        for (int i = 0; i < buckets.length; i++) {
            Node<K, V> n = buckets[i];
            while (n != null) {
                Node<K, V> next = n.next;
                int idx = indexFor(hash(n.key), newCapacity);
                n.next = newBuckets[idx];
                newBuckets[idx] = n;
                n = next;
            }
        }
        buckets = newBuckets;
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return (hash & 0x7fffffff) % length;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }
}
