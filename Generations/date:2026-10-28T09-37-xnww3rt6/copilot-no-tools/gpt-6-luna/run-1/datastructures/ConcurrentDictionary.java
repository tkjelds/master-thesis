@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
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
    private Object[] buckets;
    private int size;
    private int resizeThreshold;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        buckets = new Object[capacity];
        resizeThreshold = thresholdFor(capacity);
    }

    public V put(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (buckets.length - 1);

            Entry<K, V> entry = entryAt(index);
            while (entry != null) {
                if (entry.hash == hash && keysEqual(entry.key, key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
                entry = entry.next;
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary size limit reached");
            }

            if (size + 1 > resizeThreshold && buckets.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (buckets.length - 1);
            }

            buckets[index] = new Entry<K, V>(hash, key, value, entryAt(index));
            size++;
            return null;
        }
    }

    public V get(K key) {
        synchronized (lock) {
            int hash = hash(key);
            Entry<K, V> entry = entryAt(hash & (buckets.length - 1));

            while (entry != null) {
                if (entry.hash == hash && keysEqual(entry.key, key)) {
                    return entry.value;
                }
                entry = entry.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            int hash = hash(key);
            Entry<K, V> entry = entryAt(hash & (buckets.length - 1));

            while (entry != null) {
                if (entry.hash == hash && keysEqual(entry.key, key)) {
                    return true;
                }
                entry = entry.next;
            }
            return false;
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (buckets.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> entry = entryAt(index);

            while (entry != null) {
                if (entry.hash == hash && keysEqual(entry.key, key)) {
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Entry<K, V> entryAt(int index) {
        return (Entry<K, V>) buckets[index];
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int newCapacity = buckets.length << 1;
        Object[] newBuckets = new Object[newCapacity];

        for (int i = 0; i < buckets.length; i++) {
            Entry<K, V> entry = entryAt(i);
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = entry.hash & (newCapacity - 1);
                entry.next = (Entry<K, V>) newBuckets[index];
                newBuckets[index] = entry;
                entry = next;
            }
        }

        buckets = newBuckets;
        resizeThreshold = thresholdFor(newCapacity);
    }

    private static int thresholdFor(int capacity) {
        return capacity - (capacity >>> 2);
    }

    private static int hash(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
