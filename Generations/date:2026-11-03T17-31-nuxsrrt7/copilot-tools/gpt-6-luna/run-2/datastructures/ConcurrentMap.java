@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Entry<K, V>[] buckets;
    private int size;

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

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        buckets = (Entry<K, V>[]) new Entry[initialCapacity];
    }

    public V get(K key) {
        synchronized (lock) {
            int hash = hash(key);
            Entry<K, V> entry = find(key, hash);
            return entry == null ? null : entry.value;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            return find(key, hash(key)) != null;
        }
    }

    public V put(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            Entry<K, V> entry = find(key, hash);
            if (entry != null) {
                V previous = entry.value;
                entry.value = value;
                return previous;
            }

            if (size >= threshold(buckets.length) && buckets.length < MAX_CAPACITY) {
                resize();
            }

            int index = indexFor(hash, buckets.length);
            buckets[index] = new Entry<K, V>(key, value, hash, buckets[index]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            Entry<K, V> previous = null;
            Entry<K, V> entry = buckets[index];

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

    private Entry<K, V> find(K key, int hash) {
        int index = indexFor(hash, buckets.length);
        Entry<K, V> entry = buckets[index];
        while (entry != null) {
            if (entry.hash == hash && keysEqual(entry.key, key)) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
    }

    private void resize() {
        int newCapacity = buckets.length > MAX_CAPACITY / 2
                ? MAX_CAPACITY
                : buckets.length * 2;

        @SuppressWarnings("unchecked")
        Entry<K, V>[] newBuckets = (Entry<K, V>[]) new Entry[newCapacity];

        for (Entry<K, V> head : buckets) {
            Entry<K, V> entry = head;
            while (entry != null) {
                Entry<K, V> next = entry.next;
                int index = indexFor(entry.hash, newCapacity);
                entry.next = newBuckets[index];
                newBuckets[index] = entry;
                entry = next;
            }
        }

        buckets = newBuckets;
    }

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static int indexFor(int hash, int capacity) {
        return (hash & 0x7fffffff) % capacity;
    }

    private static int threshold(int capacity) {
        return Math.max(1, capacity - (capacity >>> 2));
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
