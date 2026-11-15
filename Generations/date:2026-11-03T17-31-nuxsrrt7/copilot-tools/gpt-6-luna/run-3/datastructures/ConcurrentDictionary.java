@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

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

    private final Object lock = new Object();
    private Entry<K, V>[] table;
    private int threshold;
    private int size;

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary() {
        table = (Entry<K, V>[]) new Entry[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (lock) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);

            for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary is too large");
            }

            ensureCapacityForOneMore();
            index = hash & (table.length - 1);
            table[index] = new Entry<K, V>(key, value, hash, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spread(key.hashCode());
            Entry<K, V> entry = findEntry(key, hash);
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spread(key.hashCode());
            return findEntry(key, hash) != null;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = spread(key.hashCode());
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

    private Entry<K, V> findEntry(K key, int hash) {
        int index = hash & (table.length - 1);
        for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.key.equals(key)) {
                return entry;
            }
        }
        return null;
    }

    private void ensureCapacityForOneMore() {
        if (size + 1 <= threshold || table.length == MAX_CAPACITY) {
            return;
        }

        resize(table.length << 1);
    }

    @SuppressWarnings("unchecked")
    private void resize(int newCapacity) {
        Entry<K, V>[] newTable = (Entry<K, V>[]) new Entry[newCapacity];

        for (Entry<K, V> bucket : table) {
            Entry<K, V> entry = bucket;
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = entry.hash & (newCapacity - 1);
                entry.next = newTable[index];
                newTable[index] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
