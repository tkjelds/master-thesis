/**
 * A thread-safe, unbounded skip-list map ordered by the natural ordering of keys.
 *
 * Null keys and values are not permitted. A null return from a lookup, removal,
 * or navigation operation means that no matching mapping or key exists.
 * Operations do not wait for entries or capacity. If the number of entries
 * reaches Integer.MAX_VALUE, an insertion of a new key fails.
 *
 * Keys must retain a stable ordering while stored in the map. Their compareTo
 * implementations must not mutate this map.
 */
@SkipListMap.ThreadSafe
public final class SkipListMap<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        Node(K key, V value, int levels) {
            this.key = key;
            this.value = value;
            this.next = newNodeArray(levels);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newNodeArray(int length) {
        return (Node<K, V>[]) new Node<?, ?>[length];
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int levels = 1;
    private int size;
    private long randomState = 0x9E3779B97F4A7C15L;

    public SkipListMap() {
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private void requireValue(V value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    // Called only while holding lock.
    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL) {
            long x = randomState;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            randomState = x;
            if ((x & 1L) == 0L) {
                break;
            }
            result++;
        }
        return result;
    }

    // Called only while holding lock.
    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }
        current = current.next[0];
        if (current != null && current.key.compareTo(key) == 0) {
            return current;
        }
        return null;
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            return insert(key, value, false);
        }
    }

    public V putIfAbsent(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            return insert(key, value, true);
        }
    }

    // Called only while holding lock.
    private V insert(K key, V value, boolean onlyIfAbsent) {
        Node<K, V>[] update = newNodeArray(MAX_LEVEL);
        Node<K, V> current = head;

        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            update[i] = current;
        }

        Node<K, V> candidate = current.next[0];
        if (candidate != null && candidate.key.compareTo(key) == 0) {
            V oldValue = candidate.value;
            if (!onlyIfAbsent) {
                candidate.value = value;
            }
            return oldValue;
        }

        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("map size limit reached");
        }

        int newLevels = randomLevel();
        Node<K, V> node = new Node<K, V>(key, value, newLevels);
        for (int i = levels; i < newLevels; i++) {
            update[i] = head;
        }
        for (int i = 0; i < newLevels; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
        if (newLevels > levels) {
            levels = newLevels;
        }
        size++;
        return null;
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] update = newNodeArray(MAX_LEVEL);
            Node<K, V> current = head;

            for (int i = levels - 1; i >= 0; i--) {
                while (current.next[i] != null
                        && current.next[i].key.compareTo(key) < 0) {
                    current = current.next[i];
                }
                update[i] = current;
            }

            Node<K, V> candidate = current.next[0];
            if (candidate == null || candidate.key.compareTo(key) != 0) {
                return null;
            }

            for (int i = 0; i < levels; i++) {
                if (update[i].next[i] == candidate) {
                    update[i].next[i] = candidate.next[i];
                }
            }
            while (levels > 1 && head.next[levels - 1] == null) {
                levels--;
            }
            size--;
            return candidate.value;
        }
    }

    public V replace(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            if (node == null) {
                return null;
            }
            V oldValue = node.value;
            node.value = value;
            return oldValue;
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
            for (int i = 0; i < levels; i++) {
                head.next[i] = null;
            }
            levels = 1;
            size = 0;
        }
    }

    public K firstKey() {
        synchronized (lock) {
            Node<K, V> first = head.next[0];
            return first == null ? null : first.key;
        }
    }

    public K lastKey() {
        synchronized (lock) {
            Node<K, V> current = head;
            for (int i = levels - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : current.key;
        }
    }

    public K ceilingKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return successorKey(key, false);
        }
    }

    public K higherKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return successorKey(key, true);
        }
    }

    // Called only while holding lock.
    private K successorKey(K key, boolean strict) {
        Node<K, V> current = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null) {
                int comparison = current.next[i].key.compareTo(key);
                if (comparison < 0 || (strict && comparison == 0)) {
                    current = current.next[i];
                } else {
                    break;
                }
            }
        }
        Node<K, V> result = current.next[0];
        return result == null ? null : result.key;
    }

    public K floorKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return predecessorKey(key, false);
        }
    }

    public K lowerKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return predecessorKey(key, true);
        }
    }

    // Called only while holding lock.
    private K predecessorKey(K key, boolean strict) {
        Node<K, V> current = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (current.next[i] != null) {
                int comparison = current.next[i].key.compareTo(key);
                if (comparison < 0 || (!strict && comparison == 0)) {
                    current = current.next[i];
                } else {
                    break;
                }
            }
        }
        return current == head ? null : current.key;
    }
}
