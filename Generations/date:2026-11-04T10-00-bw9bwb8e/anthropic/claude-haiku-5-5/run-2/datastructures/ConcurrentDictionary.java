@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}


    private static final int DEFAULT_CAPACITY = 16;
    private static final int DEFAULT_MAX_SIZE = Integer.MAX_VALUE;

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

    // Single intrinsic monitor guarding all mutable state below.
    private final Object lock = new Object();

    // Maximum number of entries; put() blocks while the dictionary is full and the key is new.
    private final int maxSize;

    // Guarded by lock.
    private Node<K, V>[] table;
    private int size;

    /*
     * Policy summary:
     *  - Null keys and null values are rejected with NullPointerException.
     *  - get, containsKey, remove, tryPut, size never block.
     *  - put blocks (wait) while the dictionary is full and the key is absent.
     *  - take blocks (wait) until the key is present, then removes and returns its value.
     *  - Every state change that can unblock a waiter calls notifyAll on the lock.
     *  - Waits are guarded by while loops and re-check the condition after waking.
     *  - Interruption during a blocking operation restores the interrupt flag and
     *    propagates InterruptedException to the caller.
     */

    public ConcurrentDictionary() {
        this(DEFAULT_MAX_SIZE, DEFAULT_CAPACITY);
    }

    public ConcurrentDictionary(int maxSize, int initialBuckets) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (initialBuckets <= 0) {
            throw new IllegalArgumentException("initialBuckets must be positive");
        }
        this.maxSize = maxSize;
        int buckets = 1;
        while (buckets < initialBuckets && buckets < (1 << 30)) {
            buckets <<= 1;
        }
        this.table = newTable(buckets);
        this.size = 0;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int length) {
        return (Node<K, V>[]) new Node[length];
    }

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return (hash & 0x7fffffff) % length;
    }

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }

    // Must be called while holding lock.
    private Node<K, V> findNode(K key, int hash) {
        int idx = indexFor(hash, table.length);
        for (Node<K, V> n = table[idx]; n != null; n = n.next) {
            if (n.hash == hash && n.key.equals(key)) {
                return n;
            }
        }
        return null;
    }

    // Must be called while holding lock. Caller guarantees key is absent.
    private void insertNew(K key, int hash, V value) {
        int idx = indexFor(hash, table.length);
        table[idx] = new Node<>(hash, key, value, table[idx]);
        size++;
        if (size > table.length / 4 * 3 && table.length < (1 << 30)) {
            resize();
        }
    }

    // Must be called while holding lock.
    private void resize() {
        Node<K, V>[] oldTable = table;
        Node<K, V>[] newTable = newTable(oldTable.length * 2);
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

    // Must be called while holding lock.
    private V removeNode(K key, int hash) {
        int idx = indexFor(hash, table.length);
        Node<K, V> prev = null;
        for (Node<K, V> n = table[idx]; n != null; prev = n, n = n.next) {
            if (n.hash == hash && n.key.equals(key)) {
                if (prev == null) {
                    table[idx] = n.next;
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

    /**
     * Inserts or replaces the mapping. If the key is new and the dictionary is full,
     * blocks until space becomes available or the thread is interrupted.
     * Returns the previous value, or null if there was none.
     */
    public V put(K key, V value) throws InterruptedException {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        int hash = spread(key.hashCode());
        synchronized (lock) {
            while (true) {
                Node<K, V> existing = findNode(key, hash);
                if (existing != null) {
                    V old = existing.value;
                    existing.value = value;
                    return old;
                }
                if (size < maxSize) {
                    insertNew(key, hash, value);
                    lock.notifyAll();
                    return null;
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Non-blocking insert or replace. Returns false only when the key is new and the
     * dictionary is full; otherwise returns true.
     */
    public boolean tryPut(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        int hash = spread(key.hashCode());
        synchronized (lock) {
            Node<K, V> existing = findNode(key, hash);
            if (existing != null) {
                existing.value = value;
                return true;
            }
            if (size < maxSize) {
                insertNew(key, hash, value);
                lock.notifyAll();
                return true;
            }
            return false;
        }
    }

    /** Non-blocking lookup. Returns the value or null if absent. */
    public V get(K key) {
        requireNonNull(key, "key");
        int hash = spread(key.hashCode());
        synchronized (lock) {
            Node<K, V> n = findNode(key, hash);
            return n == null ? null : n.value;
        }
    }

    /** Non-blocking membership test. */
    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        int hash = spread(key.hashCode());
        synchronized (lock) {
            return findNode(key, hash) != null;
        }
    }

    /** Non-blocking removal. Returns the removed value or null if absent. */
    public V remove(K key) {
        requireNonNull(key, "key");
        int hash = spread(key.hashCode());
        synchronized (lock) {
            V removed = removeNode(key, hash);
            if (removed != null) {
                lock.notifyAll();
            }
            return removed;
        }
    }

    /**
     * Blocking removal. Waits until the key is present, then removes it and returns
     * its value. Throws InterruptedException if interrupted while waiting.
     */
    public V take(K key) throws InterruptedException {
        requireNonNull(key, "key");
        int hash = spread(key.hashCode());
        synchronized (lock) {
            while (true) {
                Node<K, V> n = findNode(key, hash);
                if (n != null) {
                    V removed = removeNode(key, hash);
                    lock.notifyAll();
                    return removed;
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Non-blocking removal of all entries. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                Node<K, V> n = table[i];
                while (n != null) {
                    Node<K, V> next = n.next;
                    n.next = null;
                    n = next;
                }
                table[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    /** Returns the current number of entries. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the dictionary has no entries. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }
}
