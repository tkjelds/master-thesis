@ConcurrentSkipListMap.ThreadSafe
@SuppressWarnings("unchecked")
public final class ConcurrentSkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[levels];
        }
    }

    // Single intrinsic monitor guards all mutable state (head links, level, size, seed).
    private final Object lock = new Object();
    private final Node<K, V> head = new Node<>(null, null, MAX_LEVEL);
    private int level = 1;
    private int size = 0;
    private long seed;

    /*
     * Policy:
     *  - The map is unbounded, so put/remove/get/containsKey/size never block.
     *  - Null keys and null values are rejected with NullPointerException.
     *  - get/remove return null when the key is absent.
     *  - awaitKey(K) blocks (wait/notifyAll, guarded by a while loop) until the key
     *    is present, then returns its value. It is woken by every put.
     */
    public ConcurrentSkipListMap() {
        this.seed = System.nanoTime() ^ 0x9E3779B97F4A7C15L;
        if (this.seed == 0L) {
            this.seed = 0x2545F4914F6CDD1DL;
        }
    }

    public V put(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        synchronized (lock) {
            Node<K, V>[] update = newLevels(MAX_LEVEL);
            Node<K, V> candidate = findPredecessors(key, update);
            if (candidate != null && candidate.key.compareTo(key) == 0) {
                V old = candidate.value;
                candidate.value = value;
                lock.notifyAll();
                return old;
            }

            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = head;
                }
                level = newLevel;
            }

            Node<K, V> node = new Node<>(key, value, newLevel);
            for (int i = 0; i < newLevel; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            size++;
            lock.notifyAll();
            return null;
        }
    }

    public V get(K key) {
        checkNotNull(key);
        synchronized (lock) {
            Node<K, V> candidate = findPredecessors(key, null);
            if (candidate != null && candidate.key.compareTo(key) == 0) {
                return candidate.value;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        checkNotNull(key);
        synchronized (lock) {
            Node<K, V> candidate = findPredecessors(key, null);
            return candidate != null && candidate.key.compareTo(key) == 0;
        }
    }

    public V remove(K key) {
        checkNotNull(key);
        synchronized (lock) {
            Node<K, V>[] update = newLevels(MAX_LEVEL);
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
            return target.value;
        }
    }

    /**
     * Blocks until the key is present and returns its associated value.
     * Waits are guarded by a while loop and woken by notifyAll on put.
     */
    public V awaitKey(K key) throws InterruptedException {
        checkNotNull(key);
        synchronized (lock) {
            while (true) {
                Node<K, V> candidate = findPredecessors(key, null);
                if (candidate != null && candidate.key.compareTo(key) == 0) {
                    return candidate.value;
                }
                lock.wait();
            }
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

    // Must be called while holding lock. Fills update[i] (if non-null) with the
    // rightmost node at level i whose key is < key, and returns the first node
    // at level 0 whose key is >= key (or null).
    private Node<K, V> findPredecessors(K key, Node<K, V>[] update) {
        Node<K, V> x = head;
        for (int i = level - 1; i >= 0; i--) {
            Node<K, V> nxt = x.next[i];
            while (nxt != null && nxt.key.compareTo(key) < 0) {
                x = nxt;
                nxt = x.next[i];
            }
            if (update != null) {
                update[i] = x;
            }
        }
        return x.next[0];
    }

    // Must be called while holding lock.
    private int randomLevel() {
        long r = nextRandom();
        int lvl = 1;
        while ((r & 1L) == 1L && lvl < MAX_LEVEL) {
            lvl++;
            r >>>= 1;
        }
        return lvl;
    }

    // xorshift64; must be called while holding lock.
    private long nextRandom() {
        long x = seed;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        seed = x;
        return x;
    }

    private static <K, V> Node<K, V>[] newLevels(int n) {
        return (Node<K, V>[]) new Node[n];
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }
}
