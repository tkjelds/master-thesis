/**
 * A thread-safe, dynamically resizing hash map.
 *
 * Null keys and values are not supported. Keys must maintain stable hash codes
 * and equality while stored in the map. Returned values are not copied.
 *
 * Operations may wait to acquire the map's intrinsic monitor, but never wait
 * for a capacity or occupancy condition.
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

    public ConcurrentMap() {
        table = newTable(INITIAL_CAPACITY);
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node<?, ?>[capacity];
    }

    private static void requireNonNull(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    // The caller must hold lock.
    private Node<K, V> findNode(Object key, int hash) {
        int index = hash & (table.length - 1);
        for (Node<K, V> node = table[index]; node != null; node = node.next) {
            if (node.hash == hash && key.equals(node.key)) {
                return node;
            }
        }
        return null;
    }

    // The caller must hold lock, and the key must not already be present.
    private void insertNew(int hash, K key, V value) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Map size limit reached");
        }
        if (size >= threshold) {
            grow();
        }
        int index = hash & (table.length - 1);
        table[index] = new Node<K, V>(hash, key, value, table[index]);
        size++;
    }

    // The caller must hold lock.
    private void grow() {
        int oldCapacity = table.length;
        if (oldCapacity == MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Node<K, V>[] newTable = newTable(newCapacity);
        for (int i = 0; i < oldCapacity; i++) {
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
                : newCapacity - (newCapacity >>> 2);
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
        requireNonNull(key);
        synchronized (lock) {
            return findNode(key, hash(key)) != null;
        }
    }

    public boolean containsValue(Object value) {
        requireNonNull(value);
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                for (Node<K, V> node = table[i]; node != null; node = node.next) {
                    if (value.equals(node.value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Returns the value for the key, or null if the key is absent. */
    public V get(Object key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key, hash(key));
            return node == null ? null : node.value;
        }
    }

    /** Associates the key with the value and returns its previous value, if any. */
    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            int h = hash(key);
            Node<K, V> node = findNode(key, h);
            if (node != null) {
                V previous = node.value;
                node.value = value;
                return previous;
            }
            insertNew(h, key, value);
            return null;
        }
    }

    /** Inserts only if absent, returning the existing value or null. */
    public V putIfAbsent(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            int h = hash(key);
            Node<K, V> node = findNode(key, h);
            if (node != null) {
                return node.value;
            }
            insertNew(h, key, value);
            return null;
        }
    }

    /** Removes the key and returns its previous value, or null if absent. */
    public V remove(Object key) {
        requireNonNull(key);
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];
            while (node != null) {
                if (node.hash == h && key.equals(node.key)) {
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

    /** Removes the mapping only if its current value equals the given value. */
    public boolean remove(Object key, Object value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];
            while (node != null) {
                if (node.hash == h && key.equals(node.key)) {
                    if (!value.equals(node.value)) {
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

    /** Replaces an existing value and returns it, or null if the key is absent. */
    public V replace(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            Node<K, V> node = findNode(key, hash(key));
            if (node == null) {
                return null;
            }
            V previous = node.value;
            node.value = value;
            return previous;
        }
    }

    /** Replaces the value only if it equals oldValue. */
    public boolean replace(K key, V oldValue, V newValue) {
        requireNonNull(key);
        requireNonNull(oldValue);
        requireNonNull(newValue);
        synchronized (lock) {
            Node<K, V> node = findNode(key, hash(key));
            if (node == null || !oldValue.equals(node.value)) {
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
