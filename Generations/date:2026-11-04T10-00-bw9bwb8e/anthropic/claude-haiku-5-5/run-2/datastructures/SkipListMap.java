/**
 * A thread-safe, bounded, ordered map implemented as a skip list.
 *
 * Concurrency policy:
 * - All state is guarded by a single private monitor object. Every access to the
 *   skip list structure, the size, and the level counter happens inside a
 *   synchronized block on that monitor, which provides mutual exclusion,
 *   visibility, and safe publication of nodes.
 * - Capacity is bounded. put(...) on a new key while the map is full BLOCKS
 *   (using wait/notifyAll inside a while loop) until space becomes available.
 *   If the key already exists, put updates the value and never blocks.
 * - get, containsKey, remove, size, and isEmpty never block. They return
 *   immediately with null / false / 0 when the key or element is absent.
 * - takeFirst() BLOCKS while the map is empty until an entry is inserted.
 * - Any state change (insert or removal) calls notifyAll so that all waiting
 *   threads re-check their guard conditions.
 * - Blocked operations throw InterruptedException if the waiting thread is
 *   interrupted; the structure is left unchanged in that case.
 * - Null keys and null values are rejected with NullPointerException, so a
 *   null return value unambiguously means "absent".
 */
@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    /** Immutable snapshot of a key/value pair returned by takeFirst(). */
    public static final class Entry<K, V> {
        private final K key;
        private final V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }
    }

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[levels];
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private final Node<K, V> head;   // sentinel; key is unused
    private int level;               // number of levels currently in use, >= 1
    private int size;                // number of entries, guarded by lock
    private long rngState;           // xorshift state, guarded by lock

    public SkipListMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.head = new Node<K, V>(null, null, MAX_LEVEL);
        this.level = 1;
        this.size = 0;
        this.rngState = 0x9E3779B97F4A7C15L ^ (System.nanoTime() | 1L);
        if (this.rngState == 0L) {
            this.rngState = 0x2545F4914F6CDD1DL;
        }
    }

    /**
     * Inserts or updates a mapping. If the key is new and the map is full,
     * this call blocks until space is available.
     *
     * @return the previous value associated with the key, or null if none
     */
    public V put(K key, V value) throws InterruptedException {
        checkKey(key);
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            while (true) {
                Node<K, V> found = findPredecessors(key, update);
                if (found != null && found.key.compareTo(key) == 0) {
                    V old = found.value;
                    found.value = value;
                    return old;
                }
                if (size < capacity) {
                    insertNode(key, value, update);
                    lock.notifyAll();
                    return null;
                }
                // Full: wait for a removal, then re-check everything from scratch.
                lock.wait();
            }
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
     * Returns true if the key is present. Never blocks.
     */
    public boolean containsKey(K key) {
        checkKey(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    /**
     * Removes the mapping for the key if present. Never blocks.
     *
     * @return the removed value, or null if the key was absent
     */
    public V remove(K key) {
        checkKey(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            Node<K, V> found = findPredecessors(key, update);
            if (found == null || found.key.compareTo(key) != 0) {
                return null;
            }
            for (int i = 0; i < found.next.length; i++) {
                update[i].next[i] = found.next[i];
            }
            shrinkLevel();
            size--;
            lock.notifyAll();
            return found.value;
        }
    }

    /**
     * Removes and returns the entry with the smallest key. Blocks while the
     * map is empty until an entry is inserted.
     */
    public Entry<K, V> takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node<K, V> first = head.next[0];
            // The first node is the minimum, so its predecessor at every level is head.
            for (int i = 0; i < first.next.length; i++) {
                head.next[i] = first.next[i];
            }
            shrinkLevel();
            size--;
            lock.notifyAll();
            return new Entry<K, V>(first.key, first.value);
        }
    }

    /** Returns the current number of entries. Never blocks. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the map has no entries. Never blocks. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Returns the maximum number of entries this map can hold. */
    public int capacity() {
        return capacity;
    }

    // ----- internal helpers; callers must hold lock -----

    private static void checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    /**
     * Fills update[i] with the rightmost node at level i whose key is smaller
     * than the given key, and returns the first node with key >= given key
     * (or null if none).
     */
    private Node<K, V> findPredecessors(K key, Node<K, V>[] update) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            Node<K, V> nxt = x.next[i];
            while (nxt != null && nxt.key.compareTo(key) < 0) {
                x = nxt;
                nxt = x.next[i];
            }
            update[i] = x;
        }
        return x.next[0];
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            Node<K, V> nxt = x.next[i];
            while (nxt != null && nxt.key.compareTo(key) < 0) {
                x = nxt;
                nxt = x.next[i];
            }
        }
        Node<K, V> candidate = x.next[0];
        if (candidate != null && candidate.key.compareTo(key) == 0) {
            return candidate;
        }
        return null;
    }

    private void insertNode(K key, V value, Node<K, V>[] update) {
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                update[i] = head;
            }
            level = lvl;
        }
        Node<K, V> node = new Node<K, V>(key, value, lvl);
        for (int i = 0; i < lvl; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
        size++;
    }

    private void shrinkLevel() {
        while (level > 1 && head.next[level - 1] == null) {
            level--;
        }
    }

    /** Geometric distribution with p = 1/2, using an xorshift64 generator. */
    private int randomLevel() {
        long x = rngState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        rngState = x;
        int lvl = 1;
        long bits = x;
        while ((bits & 1L) == 1L && lvl < MAX_LEVEL) {
            lvl++;
            bits >>>= 1;
        }
        return lvl;
    }
}
