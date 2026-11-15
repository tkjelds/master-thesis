/**
 * A thread-safe, dynamically growing hash map.
 *
 * Null keys and values are supported. Keys must have stable hashCode and equals
 * implementations while stored in the map.
 *
 * Operations acquire an intrinsic monitor but never wait for entries or space.
 * get and remove return null for both an absent key and a null-valued mapping.
 * containsKey distinguishes those cases.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object monitor = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

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

    public ConcurrentMap() {
        table = newTable(INITIAL_CAPACITY);
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public V get(K key) {
        synchronized (monitor) {
            Entry<K, V> entry = find(key, hash(key));
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        synchronized (monitor) {
            return find(key, hash(key)) != null;
        }
    }

    /**
     * Inserts or replaces a mapping and returns its previous value, or null
     * when no mapping existed.
     */
    public V put(K key, V value) {
        synchronized (monitor) {
            int hash = hash(key);
            Entry<K, V> entry = find(key, hash);

            if (entry != null) {
                V previous = entry.value;
                entry.value = value;
                return previous;
            }

            insert(key, value, hash);
            return null;
        }
    }

    /**
     * Atomically inserts when the key is absent or mapped to null.
     * Returns the previous value.
     */
    public V putIfAbsent(K key, V value) {
        synchronized (monitor) {
            int hash = hash(key);
            Entry<K, V> entry = find(key, hash);

            if (entry != null) {
                V previous = entry.value;
                if (previous == null) {
                    entry.value = value;
                }
                return previous;
            }

            insert(key, value, hash);
            return null;
        }
    }

    public V remove(K key) {
        synchronized (monitor) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> current = table[index];

            while (current != null) {
                if (current.hash == hash && keysEqual(key, current.key)) {
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

    // All instance helpers are called only while holding monitor.
    private Entry<K, V> find(K key, int hash) {
        Entry<K, V> current = table[hash & (table.length - 1)];

        while (current != null) {
            if (current.hash == hash && keysEqual(key, current.key)) {
                return current;
            }
            current = current.next;
        }

        return null;
    }

    private void insert(K key, V value, int hash) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Map size limit reached");
        }

        // Allocate before changing the map so allocation failure cannot
        // leave a partially inserted mapping.
        Entry<K, V> entry = new Entry<K, V>(hash, key, value, null);

        if (size >= threshold) {
            grow();
        }

        int index = hash & (table.length - 1);
        entry.next = table[index];
        table[index] = entry;
        size++;
    }

    private void grow() {
        int oldCapacity = table.length;

        if (oldCapacity == MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Entry<K, V>[] replacement = newTable(newCapacity);

        for (int i = 0; i < oldCapacity; i++) {
            Entry<K, V> current = table[i];

            while (current != null) {
                Entry<K, V> next = current.next;
                int index = current.hash & (newCapacity - 1);

                current.next = replacement[index];
                replacement[index] = current;
                current = next;
            }
        }

        table = replacement;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }

        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Entry<K, V>[] newTable(int capacity) {
        return (Entry<K, V>[]) new Entry<?, ?>[capacity];
    }
}
