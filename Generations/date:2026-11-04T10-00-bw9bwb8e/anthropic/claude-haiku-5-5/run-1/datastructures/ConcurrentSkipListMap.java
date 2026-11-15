@ConcurrentSkipListMap.ThreadSafe
public final class ConcurrentSkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    /**
     * Immutable snapshot of a key/value pair returned by first-element operations.
     */
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

    /*
     * Concurrency policy:
     *  - All state is guarded by the single monitor {@code lock}. Every public
     *    operation acquires it through a synchronized block, which provides
     *    mutual exclusion, happens-before ordering, and safe publication.
     *  - The structure is bounded by {@code capacity}. A put of a NEW key on a
     *    full structure blocks until space is freed by remove/takeFirst/tryPollFirst.
     *  - takeFirst blocks while the structure is empty until a put adds an entry.
     *  - Every state change that may unblock a waiter calls notifyAll, because
     *    waiters with different predicates share the same monitor.
     *  - All waits are guarded by while loops that re-check the predicate.
     *  - Non-blocking alternatives (get, containsKey, remove, tryPollFirst) never wait.
     *  - Null keys and values are rejected with NullPointerException.
     */
    private final Object lock = new Object();
    private final int capacity;
    private final Node<K, V> head;
    private int level;
    private int size;
    private long seed;

    public ConcurrentSkipListMap() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentSkipListMap(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.head = new Node<>(null, null, MAX_LEVEL);
        this.level = 1;
        this.size = 0;
        this.seed = 0x9E3779B97F4A7C15L;
    }

    /**
     * Inserts or replaces the mapping for key. If key is new and the map is full,
     * blocks until space becomes available.
     *
     * @return the previous value, or null if the key was absent
     */
    public V put(K key, V value) throws InterruptedException {
        checkNotNull(key);
        checkNotNull(value);
        synchronized (lock) {
            while (true) {
                Node<K, V>[] update = newUpdate();
                Node<K, V> found = findPredecessors(key, update);
                if (found != null && found.key.compareTo(key) == 0) {
                    V old = found.value;
                    found.value = value;
                    return old;
                }
                if (size < capacity) {
                    insertLocked(key, value, update);
                    size++;
                    lock.notifyAll();
                    return null;
                }
                lock.wait();
            }
        }
    }

    /**
     * Non-blocking lookup.
     *
     * @return the value mapped to key, or null if absent
     */
    public V get(K key) {
        checkNotNull(key);
        synchronized (lock) {
            Node<K, V> found = findNode(key);
            return found == null ? null : found.value;
        }
    }

    public boolean containsKey(K key) {
        checkNotNull(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    /**
     * Non-blocking removal.
     *
     * @return the removed value, or null if absent
     */
    public V remove(K key) {
        checkNotNull(key);
        synchronized (lock) {
            Node<K, V> removed = removeLocked(key);
            if (removed == null) {
                return null;
            }
            lock.notifyAll();
            return removed.value;
        }
    }

    /**
     * Removes and returns the entry with the smallest key. Blocks while the map is empty.
     */
    public Entry<K, V> takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node<K, V> first = head.next[0];
            removeLocked(first.key);
            lock.notifyAll();
            return new Entry<>(first.key, first.value);
        }
    }

    /**
     * Non-blocking variant of takeFirst.
     *
     * @return the removed smallest entry, or null if the map is empty
     */
    public Entry<K, V> tryPollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> first = head.next[0];
            removeLocked(first.key);
            lock.notifyAll();
            return new Entry<>(first.key, first.value);
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

    public int capacity() {
        return capacity;
    }

    // ---- internal helpers; all must be called while holding lock ----

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newUpdate() {
        return (Node<K, V>[]) new Node[MAX_LEVEL];
    }

    private Node<K, V> findNode(K key) {
        Node<K, V>[] update = newUpdate();
        Node<K, V> candidate = findPredecessors(key, update);
        if (candidate != null && candidate.key.compareTo(key) == 0) {
            return candidate;
        }
        return null;
    }

    /**
     * Fills update[i] with the rightmost node at level i whose key is smaller than key.
     * Returns the first node at level 0 with key >= the search key, or null.
     */
    private Node<K, V> findPredecessors(K key, Node<K, V>[] update) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].key.compareTo(key) < 0) {
                x = x.next[i];
            }
            update[i] = x;
        }
        for (int i = level; i < MAX_LEVEL; i++) {
            update[i] = head;
        }
        return x.next[0];
    }

    private void insertLocked(K key, V value, Node<K, V>[] update) {
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                update[i] = head;
            }
            level = lvl;
        }
        Node<K, V> node = new Node<>(key, value, lvl);
        for (int i = 0; i < lvl; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
    }

    /**
     * Unlinks the node with the given key, if present.
     *
     * @return the unlinked node, or null if absent
     */
    private Node<K, V> removeLocked(K key) {
        Node<K, V>[] update = newUpdate();
        Node<K, V> target = findPredecessors(key, update);
        if (target == null || target.key.compareTo(key) != 0) {
            return null;
        }
        for (int i = 0; i < level; i++) {
            if (update[i].next[i] == target) {
                update[i].next[i] = target.next[i];
            }
        }
        while (level > 1 && head.next[level - 1] == null) {
            level--;
        }
        size--;
        return target;
    }

    /**
     * Xorshift-based geometric level generator; guarded by lock.
     */
    private int randomLevel() {
        long s = seed;
        s ^= s << 13;
        s ^= s >>> 7;
        s ^= s << 17;
        seed = s;
        long bits = s;
        int lvl = 1;
        while ((bits & 1L) == 1L && lvl < MAX_LEVEL) {
            lvl++;
            bits >>>= 1;
        }
        return lvl;
    }
}
