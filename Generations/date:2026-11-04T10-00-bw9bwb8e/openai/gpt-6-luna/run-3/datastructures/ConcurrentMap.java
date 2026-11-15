@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int MAX_CAPACITY = 1 << 30;

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

    private final Object lock = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    public ConcurrentMap() {
        this(16);
    }

    /**
     * Creates a map with a capacity at least as large as the requested capacity.
     * A capacity of zero is treated as one.
     */
    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity < 0 || initialCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }

        int capacity = 1;
        int requested = initialCapacity == 0 ? 1 : initialCapacity;
        while (capacity < requested) {
            capacity <<= 1;
        }

        table = (Entry<K, V>[]) new Entry[capacity];
        threshold = calculateThreshold(capacity);
    }

    public V get(K key) {
        synchronized (lock) {
            Entry<K, V> entry = find(key, hash(key));
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            return find(key, hash(key)) != null;
        }
    }

    public boolean containsValue(V value) {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                for (Entry<K, V> entry = table[i]; entry != null; entry = entry.next) {
                    if (valuesEqual(entry.value, value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Associates the value with the key and returns the previous value, or null
     * if the key was not present. Null keys and values are permitted.
     */
    public V put(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            Entry<K, V> existing = find(key, hash);
            if (existing != null) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            if (size >= threshold && table.length < MAX_CAPACITY) {
                resize(table.length << 1);
            }

            int index = indexFor(hash, table.length);
            table[index] = new Entry<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    /**
     * Adds the mapping only if the key is absent or currently mapped to null.
     * Returns the previous value, if any.
     */
    public V putIfAbsent(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            Entry<K, V> existing = find(key, hash);
            if (existing != null && existing.value != null) {
                return existing.value;
            }
            if (existing != null) {
                existing.value = value;
                return null;
            }

            if (size >= threshold && table.length < MAX_CAPACITY) {
                resize(table.length << 1);
            }

            int index = indexFor(hash, table.length);
            table[index] = new Entry<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    /**
     * Removes the key if present and returns its previous value, or null if it
     * was absent.
     */
    public V remove(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, table.length);
            Entry<K, V> previous = null;
            Entry<K, V> entry = table[index];

            while (entry != null) {
                if (entry.hash == hash && keysEqual(entry.key, key)) {
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

    public boolean remove(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, table.length);
            Entry<K, V> previous = null;
            Entry<K, V> entry = table[index];

            while (entry != null) {
                if (entry.hash == hash
                        && keysEqual(entry.key, key)
                        && valuesEqual(entry.value, value)) {
                    if (previous == null) {
                        table[index] = entry.next;
                    } else {
                        previous.next = entry.next;
                    }
                    size--;
                    return true;
                }
                previous = entry;
                entry = entry.next;
            }
            return false;
        }
    }

    /**
     * Replaces the value for a present key and returns the previous value,
     * or null if the key was absent.
     */
    public V replace(K key, V value) {
        synchronized (lock) {
            Entry<K, V> entry = find(key, hash(key));
            if (entry == null) {
                return null;
            }
            V previous = entry.value;
            entry.value = value;
            return previous;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        synchronized (lock) {
            Entry<K, V> entry = find(key, hash(key));
            if (entry == null || !valuesEqual(entry.value, oldValue)) {
                return false;
            }
            entry.value = newValue;
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
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    private Entry<K, V> find(K key, int hash) {
        int index = indexFor(hash, table.length);
        for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
            if (entry.hash == hash && keysEqual(entry.key, key)) {
                return entry;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize(int newCapacity) {
        Entry<K, V>[] newTable = (Entry<K, V>[]) new Entry[newCapacity];

        for (int i = 0; i < table.length; i++) {
            Entry<K, V> entry = table[i];
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = indexFor(entry.hash, newCapacity);
                entry.next = newTable[index];
                newTable[index] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = calculateThreshold(newCapacity);
    }

    private static int calculateThreshold(int capacity) {
        if (capacity == MAX_CAPACITY) {
            return Integer.MAX_VALUE;
        }
        return capacity - capacity / 4;
    }

    private static int indexFor(int hash, int length) {
        return hash & (length - 1);
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

    private static boolean valuesEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
