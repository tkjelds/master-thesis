/**
 * An unbounded, thread-safe skip-list map ordered by the natural ordering of
 * its non-null keys. Values must also be non-null.
 *
 * Operations do not wait for entries or capacity, but may wait to acquire the
 * map's monitor. Returned values are not copied. Keys must not be modified in
 * a way that changes their ordering while they are in the map.
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
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private int randomHeight() {
        int height = 1;
        while (height < MAX_LEVEL) {
            randomState ^= randomState << 13;
            randomState ^= randomState >>> 7;
            randomState ^= randomState << 17;
            if ((randomState & 1L) == 0) {
                break;
            }
            height++;
        }
        return height;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] newPath() {
        return (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];
    }

    private Node<K, V> findPredecessor(K key) {
        Node<K, V> current = head;
        for (int level = levels - 1; level >= 0; level--) {
            while (current.next[level] != null
                    && current.next[level].key.compareTo(key) < 0) {
                current = current.next[level];
            }
        }
        return current;
    }

    /** Returns the value for key, or null if key is absent. */
    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> candidate = findPredecessor(key).next[0];
            if (candidate != null && candidate.key.compareTo(key) == 0) {
                return candidate.value;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> candidate = findPredecessor(key).next[0];
            return candidate != null && candidate.key.compareTo(key) == 0;
        }
    }

    /**
     * Associates key with value and returns its previous value, or null if
     * key was absent. Comparison-equivalent keys denote the same entry.
     */
    public V put(K key, V value) {
        requireKey(key);
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (lock) {
            Node<K, V>[] path = newPath();
            Node<K, V> current = head;
            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                path[level] = current;
            }
            Node<K, V> candidate = current.next[0];
            if (candidate != null && candidate.key.compareTo(key) == 0) {
                V previous = candidate.value;
                candidate.value = value;
                return previous;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("map is too large");
            }
            int height = randomHeight();
            Node<K, V> added = new Node<K, V>(key, value, height);
            for (int level = levels; level < height; level++) {
                path[level] = head;
            }
            for (int level = 0; level < height; level++) {
                added.next[level] = path[level].next[level];
                path[level].next[level] = added;
            }
            if (height > levels) {
                levels = height;
            }
            size++;
            return null;
        }
    }

    /** Removes key and returns its previous value, or null if it was absent. */
    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] path = newPath();
            Node<K, V> current = head;
            for (int level = levels - 1; level >= 0; level--) {
                while (current.next[level] != null
                        && current.next[level].key.compareTo(key) < 0) {
                    current = current.next[level];
                }
                path[level] = current;
            }
            Node<K, V> candidate = current.next[0];
            if (candidate == null || candidate.key.compareTo(key) != 0) {
                return null;
            }
            for (int level = 0; level < candidate.next.length; level++) {
                path[level].next[level] = candidate.next[level];
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
            for (int level = 0; level < levels; level++) {
                head.next[level] = null;
            }
            levels = 1;
            size = 0;
        }
    }

    /** Returns the least key, or null if the map is empty. */
    public K firstKey() {
        synchronized (lock) {
            return head.next[0] == null ? null : head.next[0].key;
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

    /** Returns the greatest key not greater than key, or null if none exists. */
    public K floorKey(K key) {
        requireKey(key);
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

    /** Returns the least key not less than key, or null if none exists. */
    public K ceilingKey(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> candidate = findPredecessor(key).next[0];
            return candidate == null ? null : candidate.key;
        }
    }
}
