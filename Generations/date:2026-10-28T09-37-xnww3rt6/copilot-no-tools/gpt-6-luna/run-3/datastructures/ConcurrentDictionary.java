@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

    private static final class Entry<K, V> {
        final K key;
        V value;
        Entry<K, V> next;

        Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Entry<K, V>[] buckets;
    private int size;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        buckets = (Entry<K, V>[]) new Entry[initialCapacity];
    }

    public V put(K key, V value) {
        requireKey(key);
        synchronized (lock) {
            int index = indexFor(key, buckets.length);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
            }

            resizeIfNeeded();
            index = indexFor(key, buckets.length);
            buckets[index] = new Entry<K, V>(key, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            for (Entry<K, V> entry = buckets[indexFor(key, buckets.length)];
                 entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    return entry.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            for (Entry<K, V> entry = buckets[indexFor(key, buckets.length)];
                 entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            int index = indexFor(key, buckets.length);
            Entry<K, V> previous = null;
            Entry<K, V> current = buckets[index];

            while (current != null) {
                if (current.key.equals(key)) {
                    if (previous == null) {
                        buckets[index] = current.next;
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
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (lock) {
            buckets = (Entry<K, V>[]) new Entry[buckets.length];
            size = 0;
        }
    }

    private void resizeIfNeeded() {
        int capacity = buckets.length;
        if (size + 1 <= capacity - (capacity >>> 2)
                || capacity > Integer.MAX_VALUE / 2) {
            return;
        }

        @SuppressWarnings("unchecked")
        Entry<K, V>[] expanded = (Entry<K, V>[]) new Entry[capacity * 2];

        for (Entry<K, V> head : buckets) {
            Entry<K, V> entry = head;
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = indexFor(entry.key, expanded.length);
                entry.next = expanded[index];
                expanded[index] = entry;
                entry = next;
            }
        }
        buckets = expanded;
    }

    private static int indexFor(Object key, int capacity) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return (hash & 0x7fffffff) % capacity;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
    }
}
