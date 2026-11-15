@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_BUCKET_COUNT = 16;

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

    private final Entry<K, V>[] buckets;
    private final Object sizeLock = new Object();
    private int entryCount;

    public ConcurrentMap() {
        this(DEFAULT_BUCKET_COUNT);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        buckets = (Entry<K, V>[]) new Entry[bucketCount];
    }

    public V put(K key, V value) {
        requireKey(key);
        int index = bucketIndex(key);
        synchronized (buckets) {
            // The array itself is immutable; synchronize on the selected bucket below.
        }
        synchronized (bucketLock(index)) {
            Entry<K, V> entry = buckets[index];
            while (entry != null) {
                if (entry.key.equals(key)) {
                    V previous = entry.value;
                    entry.value = value;
                    return previous;
                }
                entry = entry.next;
            }

            buckets[index] = new Entry<K, V>(key, value, buckets[index]);
            synchronized (sizeLock) {
                entryCount++;
            }
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);
        int index = bucketIndex(key);
        synchronized (bucketLock(index)) {
            Entry<K, V> entry = buckets[index];
            while (entry != null) {
                if (entry.key.equals(key)) {
                    return entry.value;
                }
                entry = entry.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        int index = bucketIndex(key);
        synchronized (bucketLock(index)) {
            Entry<K, V> entry = buckets[index];
            while (entry != null) {
                if (entry.key.equals(key)) {
                    return true;
                }
                entry = entry.next;
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);
        int index = bucketIndex(key);
        synchronized (bucketLock(index)) {
            Entry<K, V> previous = null;
            Entry<K, V> entry = buckets[index];

            while (entry != null) {
                if (entry.key.equals(key)) {
                    if (previous == null) {
                        buckets[index] = entry.next;
                    } else {
                        previous.next = entry.next;
                    }
                    synchronized (sizeLock) {
                        entryCount--;
                    }
                    return entry.value;
                }
                previous = entry;
                entry = entry.next;
            }
            return null;
        }
    }

    public int size() {
        synchronized (sizeLock) {
            return entryCount;
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private Object bucketLock(int index) {
        return bucketLocks[index];
    }

    private final Object[] bucketLocks = createBucketLocks();

    private Object[] createBucketLocks() {
        Object[] locks = new Object[buckets.length];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private int bucketIndex(K key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return (hash & 0x7fffffff) % buckets.length;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }
}
