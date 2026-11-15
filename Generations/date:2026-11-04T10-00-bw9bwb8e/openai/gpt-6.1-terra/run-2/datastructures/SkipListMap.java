/**
 * A thread-safe, naturally ordered skip-list map.
 *
 * Keys and values must be non-null. Keys must have a stable, consistent
 * natural ordering while stored in the map. Returned keys and values are
 * references; synchronization of their mutable state is the caller's
 * responsibility.
 *
 * Operations acquire a single intrinsic lock. They do not wait for entries
 * to be added or removed. Lookup and removal return null when no entry exists.
 * The map is unbounded except for the maximum size representable by an int;
 * inserting a new entry at that limit throws IllegalStateException.
 */
@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int height) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node<?, ?>[height];
        }
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int levels = 1;
    private int size = 0;

    // Accessed only while holding lock. The nonzero state remains nonzero.
    private int randomState = 0x6d2b79f5;

    private int randomLevel() {
        int x = randomState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        randomState = x;

        int height = 1;
        while (height < MAX_LEVEL && (x & 1) == 0) {
            height++;
            x >>>= 1;
        }
        return height;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    // The caller must hold lock.
    private Node<K, V> predecessor(K key) {
        Node<K, V> current = head;
        for (int level = levels - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
        }
        return current;
    }

    /**
     * Associates key with value and returns the previous value, or null if
     * the key was not present.
     */
    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);

        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<K, V> candidate = current.next[0];
            if (candidate != null && candidate.key.compareTo(key) == 0) {
                V previous = candidate.value;
                candidate.value = value;
                return previous;
            }

            if (size == 0x7fffffff) {
                throw new IllegalStateException("Map size limit reached");
            }

            int height = randomLevel();
            Node<K, V> node = new Node<K, V>(key, value, height);
            for (int level = levels; level < height; level++) {
                update[level] = head;
            }
            for (int level = 0; level < height; level++) {
                node.next[level] = update[level].next[level];
                update[level].next[level] = node;
            }
            if (height > levels) {
                levels = height;
            }
            size++;
            return null;
        }
    }

    /** Returns the value associated with key, or null if absent. */
    public V get(K key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> candidate = predecessor(key).next[0];
            if (candidate != null && candidate.key.compareTo(key) == 0) {
                return candidate.value;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> candidate = predecessor(key).next[0];
            return candidate != null && candidate.key.compareTo(key) == 0;
        }
    }

    /** Removes key and returns its previous value, or null if absent. */
    public V remove(K key) {
        requireNonNull(key);
        synchronized (lock) {
            @SuppressWarnings("unchecked")
            Node<K, V>[] update = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];

            Node<K, V> current = head;
            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                update[level] = current;
            }

            Node<K, V> candidate = current.next[0];
            if (candidate == null || candidate.key.compareTo(key) != 0) {
                return null;
            }

            for (int level = 0; level < levels; level++) {
                if (update[level].next[level] == candidate) {
                    update[level].next[level] = candidate.next[level];
                }
            }
            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
            }
            size--;
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

    public void clear() {
        synchronized (lock) {
            for (int level = 0; level < MAX_LEVEL; level++) {
                head.next[level] = null;
            }
            levels = 1;
            size = 0;
        }
    }

    /** Returns the least key, or null if the map is empty. */
    public K firstKey() {
        synchronized (lock) {
            Node<K, V> first = head.next[0];
            return first == null ? null : first.key;
        }
    }

    /** Returns the greatest key, or null if the map is empty. */
    public K lastKey() {
        synchronized (lock) {
            Node<K, V> current = head;
            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null) {
                    current = current.next[level];
                }
            }
            return current == head ? null : current.key;
        }
    }

    /** Returns the greatest key less than or equal to key, or null. */
    public K floorKey(K key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> current = head;
            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) <= 0) {
                    current = current.next[level];
                }
            }
            return current == head ? null : current.key;
        }
    }

    /** Returns the least key greater than or equal to key, or null. */
    public K ceilingKey(K key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> candidate = predecessor(key).next[0];
            return candidate == null ? null : candidate.key;
        }
    }
}
