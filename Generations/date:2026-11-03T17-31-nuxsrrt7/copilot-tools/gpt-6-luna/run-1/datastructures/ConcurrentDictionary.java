@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private static final class Entry<K, V> {
        final K key;
        final int hash;
        V value;
        Entry<K, V> next;

        Entry(K key, int hash, V value, Entry<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    private Entry<K, V>[] buckets;
    private int size;
    private int threshold;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity < 1 || initialCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        buckets = (Entry<K, V>[]) new Entry[capacity];
        threshold = capacity - (capacity >>> 2);
    }

    public V put(K key, V value) {
        requireKeyAndValue(key, value);
        int hash = hash(key);

        synchronized (this) {
            int index = hash & (buckets.length - 1);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
            }

            if (size + 1 > threshold && buckets.length < MAX_CAPACITY) {
                resize();
                index = hash & (buckets.length - 1);
            }

            buckets[index] = new Entry<K, V>(key, hash, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (this) {
            int index = hash & (buckets.length - 1);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    return entry.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (this) {
            int index = hash & (buckets.length - 1);
            for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
                if (entry.hash == hash && entry.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);
        int hash = hash(key);

        synchronized (this) {
            int index = hash & (buckets.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> entry = buckets[index];

            while (entry != null) {
                if (entry.hash == hash && entry.key.equals(key)) {
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
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (this) {
            buckets = (Entry<K, V>[]) new Entry[buckets.length];
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Entry<K, V>[] oldBuckets = buckets;
        Entry<K, V>[] newBuckets =
                (Entry<K, V>[]) new Entry[oldBuckets.length << 1];

        for (Entry<K, V> head : oldBuckets) {
            Entry<K, V> entry = head;
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = entry.hash & (newBuckets.length - 1);
                entry.next = newBuckets[index];
                newBuckets[index] = entry;
                entry = next;
            }
        }

        buckets = newBuckets;
        threshold = newBuckets.length - (newBuckets.length >>> 2);
    }

    private static int hash(Object key) {
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Null keys are not supported");
        }
    }

    private static void requireKeyAndValue(Object key, Object value) {
        requireKey(key);
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
    }
}
