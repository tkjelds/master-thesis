@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity < 1 || initialCapacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        table = (Entry<K, V>[]) new Entry[capacity];
        threshold = calculateThreshold(capacity);
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (table.length - 1);

            for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    V previousValue = entry.value;
                    entry.value = value;
                    return previousValue;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Map is too large");
            }

            if (size >= threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (table.length - 1);
            }

            table[index] = new Entry<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (table.length - 1);

            for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    return entry.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (table.length - 1);

            for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spreadHash(key.hashCode());
            int index = hash & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> entry = table[index];

            while (entry != null) {
                if (entry.hash == hash && entry.key.equals(key)) {
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

    private void resize() {
        int newCapacity = table.length << 1;
        if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        }

        @SuppressWarnings("unchecked")
        Entry<K, V>[] newTable = (Entry<K, V>[]) new Entry[newCapacity];

        for (Entry<K, V> head : table) {
            Entry<K, V> entry = head;
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = entry.hash & (newCapacity - 1);
                entry.next = newTable[index];
                newTable[index] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = calculateThreshold(newCapacity);
    }

    private static int calculateThreshold(int capacity) {
        if (capacity == MAXIMUM_CAPACITY) {
            return Integer.MAX_VALUE;
        }
        return capacity - (capacity >>> 2);
    }

    private static int spreadHash(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    private static final class Entry<K, V> {
        private final int hash;
        private final K key;
        private V value;
        private Entry<K, V> next;

        private Entry(int hash, K key, V value, Entry<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
