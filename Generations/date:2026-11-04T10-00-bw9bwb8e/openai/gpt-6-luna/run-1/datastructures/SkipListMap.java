@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[level];
        }
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);

    // Accessed only while holding lock.
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public SkipListMap() {
    }

    /**
     * Associates value with key and returns the previous value, or null if
     * there was no previous mapping. Null keys are not permitted; null values
     * are permitted.
     */
    public V put(K key, V value) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] update = newNodeArray();
            Node<K, V> current = findPredecessors(key, update);
            if (current != null && current.key.compareTo(key) == 0) {
                V previous = current.value;
                current.value = value;
                return previous;
            }

            int level = randomLevel();
            Node<K, V> inserted = new Node<K, V>(key, value, level);
            for (int i = 0; i < level; i++) {
                inserted.next[i] = update[i].next[i];
                update[i].next[i] = inserted;
            }
            size++;
            return null;
        }
    }

    /**
     * Returns the value associated with key, or null if no mapping exists.
     * Use containsKey to distinguish an absent mapping from a null value.
     */
    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> current = findPredecessors(key, null);
            if (current != null && current.key.compareTo(key) == 0) {
                return current.value;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> current = findPredecessors(key, null);
            return current != null && current.key.compareTo(key) == 0;
        }
    }

    /**
     * Removes key and returns its previous value, or null if no mapping
     * existed. Null values are permitted.
     */
    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] update = newNodeArray();
            Node<K, V> current = findPredecessors(key, update);
            if (current == null || current.key.compareTo(key) != 0) {
                return null;
            }

            for (int i = 0; i < current.next.length; i++) {
                if (update[i].next[i] == current) {
                    update[i].next[i] = current.next[i];
                }
            }
            size--;
            return current.value;
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
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            size = 0;
        }
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] newNodeArray() {
        return (Node<K, V>[]) new Node[MAX_LEVEL];
    }

    /**
     * Returns the first node whose key is greater than or equal to key.
     * If update is non-null, fills it with the predecessor at each level.
     * The caller must hold lock.
     */
    private Node<K, V> findPredecessors(K key, Node<K, V>[] update) {
        Node<K, V> current = head;
        for (int level = MAX_LEVEL - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
            if (update != null) {
                update[level] = current;
            }
        }
        return current.next[0];
    }

    /**
     * Generates a level using a per-instance xorshift state.
     * The caller must hold lock.
     */
    private int randomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && (nextRandomLong() & 1L) == 0L) {
            level++;
        }
        return level;
    }

    /**
     * The caller must hold lock.
     */
    private long nextRandomLong() {
        long x = randomState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        randomState = x;
        return x;
    }
}
