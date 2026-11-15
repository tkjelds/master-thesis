/**
 * A thread-safe hash map using a single intrinsic monitor.
 *
 * Null keys and values are not permitted. Keys must have stable hashCode()
 * and equals() behavior while stored in this map.
 *
 * Operations are atomic and may wait to acquire the monitor, but never wait
 * for entries or free capacity. The table grows automatically as needed.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object monitor = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    private static final class Entry<K, V> {
        final K key;
        final int hash;
        V value;
        Entry<K, V> next;

        Entry(K key, V value, int hash, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0 || initialCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                "Initial capacity must be between 1 and " + MAX_CAPACITY);
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        table = (Entry<K, V>[]) new Entry<?, ?>[capacity];
        threshold = capacity - (capacity >>> 2);
    }

    /**
     * Returns the associated value, or null if the key is absent.
     */
    public V get(K key) {
        int hash = hash(key);
        synchronized (monitor) {
            Entry<K, V> entry = find(key, hash);
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        int hash = hash(key);
        synchronized (monitor) {
            return find(key, hash) != null;
        }
    }

    /**
     * Associates a value with a key, returning its previous value or null.
     */
    public V put(K key, V value) {
        requireValue(value);
        int hash = hash(key);

        synchronized (monitor) {
            Entry<K, V> existing = find(key, hash);
            if (existing != null) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            insert(key, value, hash);
            return null;
        }
    }

    /**
     * Inserts only if absent, returning the existing value or null.
     */
    public V putIfAbsent(K key, V value) {
        requireValue(value);
        int hash = hash(key);

        synchronized (monitor) {
            Entry<K, V> existing = find(key, hash);
            if (existing != null) {
                return existing.value;
            }

            insert(key, value, hash);
            return null;
        }
    }

    /**
     * Removes a key, returning its previous value or null if absent.
     */
    public V remove(K key) {
        int hash = hash(key);

        synchronized (monitor) {
            int index = hash & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> current = table[index];

            while (current != null) {
                if (current.hash == hash
                        && (key == current.key || key.equals(current.key))) {
                    if (previous == null) {
                        table[index] = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    return current.value;
                }
                previous = current;
                current = current.next;
            }

            return null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    private static int hash(Object key) {
        if (key == null) {
            throw new NullPointerException("Null keys are not supported");
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }
    }

    // The following instance helpers require ownership of monitor.
    private Entry<K, V> find(K key, int hash) {
        Entry<K, V> current = table[hash & (table.length - 1)];
        while (current != null) {
            if (current.hash == hash
                    && (key == current.key || key.equals(current.key))) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void insert(K key, V value, int hash) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Maximum map size reached");
        }

        Entry<K, V> entry = new Entry<K, V>(key, value, hash, null);
        if (size >= threshold && table.length < MAX_CAPACITY) {
            grow();
        }

        int index = hash & (table.length - 1);
        entry.next = table[index];
        table[index] = entry;
        size++;
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int capacity = table.length << 1;
        Entry<K, V>[] replacement =
            (Entry<K, V>[]) new Entry<?, ?>[capacity];

        for (int i = 0; i < table.length; i++) {
            Entry<K, V> current = table[i];
            while (current != null) {
                Entry<K, V> next = current.next;
                int index = current.hash & (capacity - 1);
                current.next = replacement[index];
                replacement[index] = current;
                current = next;
            }
        }

        table = replacement;
        threshold = capacity - (capacity >>> 2);
    }
}
