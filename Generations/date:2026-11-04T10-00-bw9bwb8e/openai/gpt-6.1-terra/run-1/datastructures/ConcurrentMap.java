/**
 * A thread-safe, unbounded hash map supporting null keys and values.
 *
 * Operations acquire an intrinsic monitor; they do not wait for entries or
 * capacity. Allocation may fail with OutOfMemoryError. Keys must have stable
 * hashCode and equals behavior while stored, and key/value callbacks must not
 * modify this map.
 *
 * Null returns from get, put, remove, and replace can mean either an absent
 * mapping or a mapping whose value is null. Use containsKey to distinguish
 * these cases when no concurrent modification intervenes.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

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

    private final Object lock = new Object();
    private Node<K, V>[] table;
    private int size;
    private int threshold;

    @SuppressWarnings("unchecked")
    public ConcurrentMap() {
        table = (Node<K, V>[]) new Node<?, ?>[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;
    }

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    // Called only while holding lock.
    private Node<K, V> findNode(int hash, Object key) {
        int index = hash & (table.length - 1);
        for (Node<K, V> node = table[index]; node != null; node = node.next) {
            if (node.hash == hash && equal(key, node.key)) {
                return node;
            }
        }
        return null;
    }

    // Called only while holding lock, after checking that the key is absent.
    private void insertNew(int hash, K key, V value) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Map has reached its maximum size");
        }
        if (size >= threshold && table.length < MAXIMUM_CAPACITY) {
            resize();
        }
        int index = hash & (table.length - 1);
        table[index] = new Node<K, V>(hash, key, value, table[index]);
        size++;
    }

    // Called only while holding lock.
    @SuppressWarnings("unchecked")
    private void resize() {
        int newCapacity = table.length << 1;
        Node<K, V>[] newTable = (Node<K, V>[]) new Node<?, ?>[newCapacity];

        for (int i = 0; i < table.length; i++) {
            Node<K, V> node = table[i];
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newCapacity - 1);
                node.next = newTable[index];
                newTable[index] = node;
                node = next;
            }
        }

        table = newTable;
        threshold = newCapacity == MAXIMUM_CAPACITY
                ? Integer.MAX_VALUE
                : newCapacity - newCapacity / 4;
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

    public boolean containsKey(Object key) {
        synchronized (lock) {
            return findNode(hash(key), key) != null;
        }
    }

    public boolean containsValue(Object value) {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                for (Node<K, V> node = table[i]; node != null; node = node.next) {
                    if (equal(value, node.value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public V get(Object key) {
        synchronized (lock) {
            Node<K, V> node = findNode(hash(key), key);
            return node == null ? null : node.value;
        }
    }

    public V getOrDefault(Object key, V defaultValue) {
        synchronized (lock) {
            Node<K, V> node = findNode(hash(key), key);
            return node == null ? defaultValue : node.value;
        }
    }

    public V put(K key, V value) {
        synchronized (lock) {
            int h = hash(key);
            Node<K, V> node = findNode(h, key);
            if (node != null) {
                V oldValue = node.value;
                node.value = value;
                return oldValue;
            }
            insertNew(h, key, value);
            return null;
        }
    }

    /**
     * Associates the value if the key is absent or currently maps to null.
     * Returns the previous value, or null if there was no non-null value.
     */
    public V putIfAbsent(K key, V value) {
        synchronized (lock) {
            int h = hash(key);
            Node<K, V> node = findNode(h, key);
            if (node != null) {
                V oldValue = node.value;
                if (oldValue == null) {
                    node.value = value;
                }
                return oldValue;
            }
            insertNew(h, key, value);
            return null;
        }
    }

    public V remove(Object key) {
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == h && equal(key, node.key)) {
                    if (previous == null) {
                        table[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return node.value;
                }
                previous = node;
                node = node.next;
            }
            return null;
        }
    }

    public boolean remove(Object key, Object value) {
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == h && equal(key, node.key)) {
                    if (!equal(value, node.value)) {
                        return false;
                    }
                    if (previous == null) {
                        table[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return true;
                }
                previous = node;
                node = node.next;
            }
            return false;
        }
    }

    public V replace(K key, V value) {
        synchronized (lock) {
            Node<K, V> node = findNode(hash(key), key);
            if (node == null) {
                return null;
            }
            V oldValue = node.value;
            node.value = value;
            return oldValue;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        synchronized (lock) {
            Node<K, V> node = findNode(hash(key), key);
            if (node == null || !equal(oldValue, node.value)) {
                return false;
            }
            node.value = newValue;
            return true;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }
}
