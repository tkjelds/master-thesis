@ThreadSafeMap.ThreadSafe
public final class ThreadSafeMap<K, V> {
    @interface ThreadSafe {}


    /*
     * Concurrency policy:
     *  - All state (table, size) is guarded by the single intrinsic monitor 'lock'.
     *    Every access to shared state happens inside a synchronized(lock) block, which
     *    provides mutual exclusion, no lost updates, and happens-before visibility
     *    between threads.
     *  - get, containsKey, size, isEmpty never block; they return immediately.
     *  - put on a NEW key blocks (wait) while the map is full (size == maxSize) until
     *    a remove/clear frees space. Replacing the value of an existing key never blocks,
     *    because it does not increase the size.
     *  - awaitKey blocks until the key is present and returns its value.
     *  - remove and clear notify all waiters; put notifies all waiters after insertion.
     *  - Every wait is guarded by a while loop that re-checks the condition.
     *  - Null keys and null values are rejected with NullPointerException.
     *  - Interruption is reported via InterruptedException; the map state is left
     *    unchanged when an operation is interrupted before completing.
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

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private final int maxSize;

    // Guarded by lock
    private Node<K, V>[] table;
    private int size;

    public ThreadSafeMap() {
        this(DEFAULT_INITIAL_CAPACITY, Integer.MAX_VALUE);
    }

    public ThreadSafeMap(int initialCapacity, int maxSize) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
        int cap = 1;
        while (cap < initialCapacity && cap < MAXIMUM_CAPACITY) {
            cap <<= 1;
        }
        this.table = newTable(cap);
        this.size = 0;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }

    private static int hashOf(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return (hash & 0x7fffffff) % length;
    }

    private static void requireNonNull(Object o, String what) {
        if (o == null) {
            throw new NullPointerException(what + " must not be null");
        }
    }

    // Must be called while holding lock.
    private Node<K, V> findNode(Object key, int hash) {
        Node<K, V> n = table[indexFor(hash, table.length)];
        while (n != null) {
            if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    // Must be called while holding lock.
    private void resizeIfNeeded() {
        if ((long) size * 4 <= (long) table.length * 3) {
            return;
        }
        if (table.length >= MAXIMUM_CAPACITY) {
            return;
        }
        Node<K, V>[] oldTable = table;
        Node<K, V>[] newTable = newTable(oldTable.length << 1);
        for (Node<K, V> head : oldTable) {
            Node<K, V> n = head;
            while (n != null) {
                Node<K, V> next = n.next;
                int idx = indexFor(n.hash, newTable.length);
                n.next = newTable[idx];
                newTable[idx] = n;
                n = next;
            }
        }
        table = newTable;
    }

    /**
     * Returns the value mapped to key, or null if absent. Never blocks.
     */
    public V get(K key) {
        requireNonNull(key, "key");
        int hash = hashOf(key);
        synchronized (lock) {
            Node<K, V> n = findNode(key, hash);
            return (n == null) ? null : n.value;
        }
    }

    /**
     * Returns true if key is present. Never blocks.
     */
    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        int hash = hashOf(key);
        synchronized (lock) {
            return findNode(key, hash) != null;
        }
    }

    /**
     * Associates value with key and returns the previous value (or null if none).
     * Replacing an existing key never blocks. Inserting a new key blocks while the
     * map is full, until space becomes available.
     */
    public V put(K key, V value) throws InterruptedException {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        int hash = hashOf(key);
        synchronized (lock) {
            while (true) {
                Node<K, V> n = findNode(key, hash);
                if (n != null) {
                    V old = n.value;
                    n.value = value;
                    return old;
                }
                if (size < maxSize) {
                    int idx = indexFor(hash, table.length);
                    table[idx] = new Node<K, V>(hash, key, value, table[idx]);
                    size++;
                    resizeIfNeeded();
                    lock.notifyAll();
                    return null;
                }
                // Map is full and key is absent: wait for space or for the key.
                lock.wait();
            }
        }
    }

    /**
     * Removes the mapping for key and returns its value, or null if absent.
     * Never blocks. Wakes blocked threads since space may have been freed.
     */
    public V remove(K key) {
        requireNonNull(key, "key");
        int hash = hashOf(key);
        synchronized (lock) {
            int idx = indexFor(hash, table.length);
            Node<K, V> prev = null;
            Node<K, V> n = table[idx];
            while (n != null) {
                if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                    if (prev == null) {
                        table[idx] = n.next;
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

    /**
     * Blocks until key is present, then returns its value. Does not remove the mapping.
     * Waits indefinitely if the key is never inserted; use interruption to abort.
     */
    public V awaitKey(K key) throws InterruptedException {
        requireNonNull(key, "key");
        int hash = hashOf(key);
        synchronized (lock) {
            while (true) {
                Node<K, V> n = findNode(key, hash);
                if (n != null) {
                    return n.value;
                }
                lock.wait();
            }
        }
    }

    /**
     * Removes all mappings and wakes blocked threads.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    /**
     * Returns the current number of mappings. Never blocks.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the map currently has no mappings. Never blocks.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }
}
