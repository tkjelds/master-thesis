@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<K>, V> {
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

    private int size;
    private int currentLevel = 1;
    private long randomState = 0x9E3779B97F4A7C15L;

    public SkipListMap() {
    }

    /**
     * Associates the value with the key, returning the previous value or null
     * if the key was not present. Keys and values must be non-null.
     */
    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (lock) {
            Node<K, V>[] update = findPredecessors(key);
            Node<K, V> candidate = update[0].next[0];

            if (candidate != null && key.compareTo(candidate.key) == 0) {
                V oldValue = candidate.value;
                candidate.value = value;
                return oldValue;
            }

            int level = randomLevel();
            if (level > currentLevel) {
                for (int i = currentLevel; i < level; i++) {
                    update[i] = head;
                }
                currentLevel = level;
            }

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
     * Returns the value associated with the key, or null if the key is absent.
     * The key must be non-null.
     */
    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            Node<K, V> candidate = findPredecessors(key)[0].next[0];
            if (candidate != null && key.compareTo(candidate.key) == 0) {
                return candidate.value;
            }
            return null;
        }
    }

    /**
     * Returns whether the key is present. The key must be non-null.
     */
    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            Node<K, V> candidate = findPredecessors(key)[0].next[0];
            return candidate != null && key.compareTo(candidate.key) == 0;
        }
    }

    /**
     * Removes the key and returns its value, or returns null if it was absent.
     * The key must be non-null.
     */
    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            Node<K, V>[] update = findPredecessors(key);
            Node<K, V> candidate = update[0].next[0];

            if (candidate == null || key.compareTo(candidate.key) != 0) {
                return null;
            }

            for (int i = 0; i < currentLevel; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }

            size--;
            while (currentLevel > 1 && head.next[currentLevel - 1] == null) {
                currentLevel--;
            }
            return candidate.value;
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
     * Removes all entries. This operation completes immediately.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < currentLevel; i++) {
                head.next[i] = null;
            }
            currentLevel = 1;
            size = 0;
        }
    }

    /**
     * Returns the smallest key, or throws IllegalStateException if empty.
     */
    public K firstKey() {
        synchronized (lock) {
            if (head.next[0] == null) {
                throw new IllegalStateException("map is empty");
            }
            return head.next[0].key;
        }
    }

    /**
     * Returns the largest key, or throws IllegalStateException if empty.
     */
    public K lastKey() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("map is empty");
            }

            Node<K, V> current = head;
            for (int i = currentLevel - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current.key;
        }
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] findPredecessors(K key) {
        Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
        Node<K, V> current = head;

        for (int i = currentLevel - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        for (int i = currentLevel; i < MAX_LEVEL; i++) {
            update[i] = head;
        }
        return update;
    }

    private int randomLevel() {
        randomState ^= randomState << 13;
        randomState ^= randomState >>> 7;
        randomState ^= randomState << 17;

        int level = 1;
        long bits = randomState;
        while (level < MAX_LEVEL && (bits & 1L) == 1L) {
            level++;
            bits >>>= 1;
        }
        return level;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }
}
