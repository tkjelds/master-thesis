/**
 * A thread-safe, unbounded skip-list map using natural key ordering.
 *
 * Null keys and values are not permitted. get and remove return null when a
 * key is absent. Navigation methods return null when no matching key exists.
 * Operations do not wait for entries or capacity.
 *
 * Keys must retain a consistent natural ordering while stored. Key comparison
 * and value equality must not modify this map reentrantly.
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
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node<?, ?>[level];
        }
    }

    private final Object lock = new Object();
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);

    // All fields below and all node links and values are protected by lock.
    private int level = 1;
    private int size = 0;
    private int randomState = 0x6d2b79f5;

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

    private int nextRandom() {
        int x = randomState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        randomState = x;
        return x;
    }

    private int randomLevel() {
        int result = 1;
        while (result < MAX_LEVEL && (nextRandom() & 3) == 0) {
            result++;
        }
        return result;
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
        }
        current = current.next[0];
        return current != null && current.key.compareTo(key) == 0
                ? current : null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] findPredecessors(K key) {
        Node<K, V>[] predecessors = (Node<K, V>[]) new Node<?, ?>[MAX_LEVEL];
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null
                    && current.next[i].key.compareTo(key) < 0) {
                current = current.next[i];
            }
            predecessors[i] = current;
        }
        return predecessors;
    }

    private V putInternal(K key, V value, boolean onlyIfAbsent) {
        Node<K, V>[] predecessors = findPredecessors(key);
        Node<K, V> existing = predecessors[0].next[0];
        if (existing != null && existing.key.compareTo(key) == 0) {
            V oldValue = existing.value;
            if (!onlyIfAbsent) {
                existing.value = value;
            }
            return oldValue;
        }

        if (size == 2147483647) {
            throw new IllegalStateException("map is too large");
        }
        int newLevel = randomLevel();
        for (int i = level; i < newLevel; i++) {
            predecessors[i] = head;
        }

        Node<K, V> inserted = new Node<K, V>(key, value, newLevel);
        for (int i = 0; i < newLevel; i++) {
            inserted.next[i] = predecessors[i].next[i];
            predecessors[i].next[i] = inserted;
        }
        if (newLevel > level) {
            level = newLevel;
        }
        size++;
        return null;
    }

    private void unlink(Node<K, V>[] predecessors, Node<K, V> node) {
        for (int i = 0; i < node.next.length; i++) {
            predecessors[i].next[i] = node.next[i];
        }
        while (level > 1 && head.next[level - 1] == null) {
            level--;
        }
        size--;
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            return putInternal(key, value, false);
        }
    }

    public V putIfAbsent(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            return putInternal(key, value, true);
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            return node == null ? null : node.value;
        }
    }

    public V getOrDefault(K key, V defaultValue) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            return node == null ? defaultValue : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V>[] predecessors = findPredecessors(key);
            Node<K, V> node = predecessors[0].next[0];
            if (node == null || node.key.compareTo(key) != 0) {
                return null;
            }
            V oldValue = node.value;
            unlink(predecessors, node);
            return oldValue;
        }
    }

    public boolean remove(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            Node<K, V>[] predecessors = findPredecessors(key);
            Node<K, V> node = predecessors[0].next[0];
            if (node == null || node.key.compareTo(key) != 0
                    || !node.value.equals(value)) {
                return false;
            }
            unlink(predecessors, node);
            return true;
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

    public boolean replace(K key, V oldValue, V newValue) {
        requireKey(key);
        requireValue(oldValue);
        requireValue(newValue);
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            if (node == null || !node.value.equals(oldValue)) {
                return false;
            }
            node.value = newValue;
            return true;
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
            for (int i = 0; i < level; i++) {
                head.next[i] = null;
            }
            level = 1;
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
            for (int i = level - 1; i >= 0; i--) {
                while (current.next[i] != null) {
                    current = current.next[i];
                }
            }
            return current == head ? null : current.key;
        }
    }

    private K lowerBound(K key, boolean inclusive) {
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null) {
                int comparison = current.next[i].key.compareTo(key);
                if (comparison < 0 || (inclusive && comparison == 0)) {
                    current = current.next[i];
                } else {
                    break;
                }
            }
        }
        return current == head ? null : current.key;
    }

    private K upperBound(K key, boolean inclusive) {
        Node<K, V> current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.next[i] != null) {
                int comparison = current.next[i].key.compareTo(key);
                if (comparison < 0 || (!inclusive && comparison == 0)) {
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
            return lowerBound(key, true);
        }
    }

    public K lowerKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return lowerBound(key, false);
        }
    }

    public K ceilingKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return upperBound(key, true);
        }
    }

    public K higherKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return upperBound(key, false);
        }
    }
}
