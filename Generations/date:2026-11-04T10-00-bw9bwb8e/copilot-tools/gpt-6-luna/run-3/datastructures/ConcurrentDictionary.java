@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

    private final Object lock = new Object();
    private Entry<K, V>[] buckets;
    private int size;
    private int resizeThreshold;

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

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        buckets = (Entry<K, V>[]) new Entry[initialCapacity];
        resizeThreshold = initialCapacity - initialCapacity / 4;
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            int index = indexFor(key, buckets.length);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
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
            int index = indexFor(key, buckets.length);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            int index = indexFor(key, buckets.length);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.key.equals(key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
            }

            if (size >= resizeThreshold
                    && buckets.length <= (Integer.MAX_VALUE - 1) / 2) {
                resize(buckets.length * 2 + 1);
                index = indexFor(key, buckets.length);
            }

            buckets[index] = new Entry<K, V>(key, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            int index = indexFor(key, buckets.length);
            Entry<K, V> previous = null;
            Entry<K, V> entry = buckets[index];

            while (entry != null) {
                if (entry.key.equals(key)) {
                    if (previous == null) {
                        buckets[index] = entry.next;
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

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (lock) {
            buckets = (Entry<K, V>[]) new Entry[buckets.length];
            size = 0;
        }
    }

    private void resize(int newCapacity) {
        @SuppressWarnings("unchecked")
        Entry<K, V>[] newBuckets = (Entry<K, V>[]) new Entry[newCapacity];

        for (Entry<K, V> head : buckets) {
            Entry<K, V> entry = head;
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = indexFor(entry.key, newCapacity);
                entry.next = newBuckets[index];
                newBuckets[index] = entry;
                entry = next;
            }
        }

        buckets = newBuckets;
        resizeThreshold = newCapacity - newCapacity / 4;
    }

    private static int indexFor(Object key, int capacity) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return (hash & 0x7fffffff) % capacity;
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }
}
