/**
 * A thread-safe, unbounded hash map with separate chaining.
 *
 * All public operations are atomic and serialized by one intrinsic monitor.
 * Operations wait only to acquire that monitor; missing keys never block.
 * Null keys and null values are supported. A null result from get, put, or
 * remove can mean either absence or a previously stored null value.
 *
 * Keys must have stable hashCode and equals implementations while stored.
 * Key and value callbacks must not modify this map.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private static final class Entry<K, V> {
        final int hash;
        final K key;
        V value;
        Entry<K, V> next;

        Entry(int hash, K key, V value, Entry<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    public ConcurrentMap() {
        table = newTable(INITIAL_CAPACITY);
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    private static <K, V> Entry<K, V>[] newTable(int capacity) {
        // The array is private and receives only Entry<K, V> instances.
        return (Entry<K, V>[]) new Entry<?, ?>[capacity];
    }

    private static int hash(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
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

    public V get(K key) {
        synchronized (monitor) {
            Entry<K, V> entry = findEntry(key, hash(key));
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        synchronized (monitor) {
            return findEntry(key, hash(key)) != null;
        }
    }

    public boolean containsValue(V value) {
        synchronized (monitor) {
            for (int i = 0; i < table.length; i++) {
                for (Entry<K, V> entry = table[i];
                     entry != null;
                     entry = entry.next) {
                    if (equal(value, entry.value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public V put(K key, V value) {
        synchronized (monitor) {
            int hash = hash(key);
            Entry<K, V> existing = findEntry(key, hash);

            if (existing != null) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            // Allocate before changing the map's contents.
            Entry<K, V> entry = new Entry<K, V>(hash, key, value, null);
            if (size >= threshold) {
                grow();
            }

            int index = hash & (table.length - 1);
            entry.next = table[index];
            table[index] = entry;
            size++;
            return null;
        }
    }

    /**
     * Atomically stores value if the key is absent or mapped to null.
     * Returns the previous value.
     */
    public V putIfAbsent(K key, V value) {
        synchronized (monitor) {
            Entry<K, V> existing = findEntry(key, hash(key));
            if (existing != null && existing.value != null) {
                return existing.value;
            }
            return put(key, value);
        }
    }

    public V remove(K key) {
        synchronized (monitor) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> entry = table[index];

            while (entry != null) {
                if (entry.hash == hash && equal(key, entry.key)) {
                    if (previous == null) {
                        table[index] = entry.next;
                    } else {
                        previous.next = entry.next;
                    }
                    size--;
                    return entry.value;
                }
                previous = entry;
                entry = entry.next;
            }
            return null;
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

    // Called only while holding monitor.
    private Entry<K, V> findEntry(K key, int hash) {
        int index = hash & (table.length - 1);
        for (Entry<K, V> entry = table[index];
             entry != null;
             entry = entry.next) {
            if (entry.hash == hash && equal(key, entry.key)) {
                return entry;
            }
        }
        return null;
    }

    // Called only while holding monitor.
    private void grow() {
        int oldCapacity = table.length;
        if (oldCapacity == MAXIMUM_CAPACITY) {
            threshold = 0x7fffffff;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Entry<K, V>[] replacement = newTable(newCapacity);

        for (int i = 0; i < oldCapacity; i++) {
            Entry<K, V> entry = table[i];
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = entry.hash & (newCapacity - 1);
                entry.next = replacement[index];
                replacement[index] = entry;
                entry = next;
            }
        }

        table = replacement;
        threshold = newCapacity - (newCapacity >>> 2);
    }
}
