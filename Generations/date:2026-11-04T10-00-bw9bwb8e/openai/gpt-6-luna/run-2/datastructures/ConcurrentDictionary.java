@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
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

    private final Object mutex = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAX_CAPACITY) {
            capacity <<= 1;
        }

        table = newTable(capacity);
        threshold = capacity - (capacity >>> 2);
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

        synchronized (mutex) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Entry<K, V> entry = table[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    V oldValue = entry.value;
                    entry.value = value;
                    return oldValue;
                }
            }

            if (size + 1 > threshold && table.length < MAX_CAPACITY) {
                resize();
                index = hash & (table.length - 1);
            }

            table[index] = new Entry<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);

        synchronized (mutex) {
            int hash = hash(key);
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
        requireKey(key);

        synchronized (mutex) {
            int hash = hash(key);
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
        requireKey(key);

        synchronized (mutex) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> current = table[index];

            while (current != null) {
                if (current.hash == hash && current.key.equals(key)) {
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
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            table = newTable(table.length);
            size = 0;
        }
    }

    private void resize() {
        int oldCapacity = table.length;
        if (oldCapacity >= MAX_CAPACITY) {
            return;
        }

        int newCapacity = oldCapacity << 1;
        Entry<K, V>[] newTable = newTable(newCapacity);

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
        threshold = newCapacity - (newCapacity >>> 2);
    }

    @SuppressWarnings("unchecked")
    private Entry<K, V>[] newTable(int capacity) {
        return (Entry<K, V>[]) new Entry[capacity];
    }

    private int hash(K key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

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
}
