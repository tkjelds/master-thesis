public final class ConcurrentMap<K, V> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        table = (Entry<K, V>[]) new Entry[capacity];
        threshold = calculateThreshold(capacity);
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized boolean containsKey(K key) {
        return findEntry(key) != null;
    }

    public synchronized V get(K key) {
        Entry<K, V> entry = findEntry(key);
        return entry == null ? null : entry.value;
    }

    public synchronized V put(K key, V value) {
        int index = indexFor(key, table.length);
        Entry<K, V> entry = table[index];

        while (entry != null) {
            if (keysEqual(entry.key, key)) {
                V previous = entry.value;
                entry.value = value;
                return previous;
            }
            entry = entry.next;
        }

        table[index] = new Entry<K, V>(key, value, table[index]);
        size++;

        if (size > threshold && table.length < MAXIMUM_CAPACITY) {
            resize();
        }

        return null;
    }

    public synchronized V putIfAbsent(K key, V value) {
        Entry<K, V> entry = findEntry(key);
        if (entry != null) {
            return entry.value;
        }

        int index = indexFor(key, table.length);
        table[index] = new Entry<K, V>(key, value, table[index]);
        size++;

        if (size > threshold && table.length < MAXIMUM_CAPACITY) {
            resize();
        }

        return null;
    }

    public synchronized V remove(K key) {
        int index = indexFor(key, table.length);
        Entry<K, V> previous = null;
        Entry<K, V> current = table[index];

        while (current != null) {
            if (keysEqual(current.key, key)) {
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

    public synchronized boolean remove(K key, V value) {
        int index = indexFor(key, table.length);
        Entry<K, V> previous = null;
        Entry<K, V> current = table[index];

        while (current != null) {
            if (keysEqual(current.key, key) && valuesEqual(current.value, value)) {
                if (previous == null) {
                    table[index] = current.next;
                } else {
                    previous.next = current.next;
                }

                size--;
                return true;
            }

            previous = current;
            current = current.next;
        }

        return false;
    }

    public synchronized boolean replace(K key, V oldValue, V newValue) {
        Entry<K, V> entry = findEntry(key);
        if (entry == null || !valuesEqual(entry.value, oldValue)) {
            return false;
        }

        entry.value = newValue;
        return true;
    }

    public synchronized V replace(K key, V value) {
        Entry<K, V> entry = findEntry(key);
        if (entry == null) {
            return null;
        }

        V previous = entry.value;
        entry.value = value;
        return previous;
    }

    public synchronized void clear() {
        for (int i = 0; i < table.length; i++) {
            table[i] = null;
        }
        size = 0;
    }

    private Entry<K, V> findEntry(K key) {
        int index = indexFor(key, table.length);
        Entry<K, V> entry = table[index];

        while (entry != null) {
            if (keysEqual(entry.key, key)) {
                return entry;
            }
            entry = entry.next;
        }

        return null;
    }

    private void resize() {
        int oldCapacity = table.length;
        int newCapacity = oldCapacity << 1;

        if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        }

        @SuppressWarnings("unchecked")
        Entry<K, V>[] newTable = (Entry<K, V>[]) new Entry[newCapacity];

        for (int i = 0; i < oldCapacity; i++) {
            Entry<K, V> entry = table[i];

            while (entry != null) {
                Entry<K, V> next = entry.next;
                int newIndex = indexFor(entry.key, newCapacity);
                entry.next = newTable[newIndex];
                newTable[newIndex] = entry;
                entry = next;
            }
        }

        table = newTable;
        threshold = calculateThreshold(newCapacity);
    }

    private static int calculateThreshold(int capacity) {
        int result = (int) (capacity * LOAD_FACTOR);
        return result < 1 ? 1 : result;
    }

    private static int indexFor(Object key, int capacity) {
        int hash = key == null ? 0 : key.hashCode();
        hash ^= hash >>> 16;
        return hash & (capacity - 1);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static boolean valuesEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static final class Entry<K, V> {
        private final K key;
        private V value;
        private Entry<K, V> next;

        private Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
