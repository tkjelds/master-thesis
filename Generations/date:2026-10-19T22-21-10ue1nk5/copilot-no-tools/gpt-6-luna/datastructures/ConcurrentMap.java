@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
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

    private final Object lock = new Object();
    private Object[] table;
    private int size;
    private int threshold;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }

        int capacity = 4;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        table = new Object[capacity];
        threshold = thresholdFor(capacity);
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        synchronized (lock) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);

            @SuppressWarnings("unchecked")
            Entry<K, V> entry = (Entry<K, V>) table[index];
            while (entry != null) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
                entry = entry.next;
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("map is full");
            }

            if (size >= threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (table.length - 1);
            }

            @SuppressWarnings("unchecked")
            Entry<K, V> first = (Entry<K, V>) table[index];
            table[index] = new Entry<K, V>(hash, key, value, first);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);

            @SuppressWarnings("unchecked")
            Entry<K, V> entry = (Entry<K, V>) table[index];
            while (entry != null) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    return entry.value;
                }
                entry = entry.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);

            @SuppressWarnings("unchecked")
            Entry<K, V> entry = (Entry<K, V>) table[index];
            while (entry != null) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    return true;
                }
                entry = entry.next;
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);

            @SuppressWarnings("unchecked")
            Entry<K, V> entry = (Entry<K, V>) table[index];
            Entry<K, V> previous = null;

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
        Object[] newTable = new Object[newCapacity];

        for (int i = 0; i < table.length; i++) {
            @SuppressWarnings("unchecked")
            Entry<K, V> entry = (Entry<K, V>) table[i];

            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = entry.hash & (newCapacity - 1);
                entry.next = (Entry<K, V>) newTable[index];
                newTable[index] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = thresholdFor(newCapacity);
    }

    private static int thresholdFor(int capacity) {
        return capacity - (capacity >>> 2);
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
    }
}
