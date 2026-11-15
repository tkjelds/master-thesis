@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    /*
     * Keys and values must be non-null. Keys must retain stable hashCode and
     * equals behavior while stored, and those methods must not modify this map.
     *
     * Operations do not wait for space or entries. They may wait to acquire
     * the map's intrinsic lock. Capacity grows as needed, subject to memory
     * availability and the maximum representable size.
     */
    private static final int INITIAL_CAPACITY = 16;
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

    private final Object lock = new Object();
    private Entry<K, V>[] table;
    private int size;
    private int threshold;

    @SuppressWarnings("unchecked")
    public ConcurrentMap() {
        table = (Entry<K, V>[]) new Entry<?, ?>[INITIAL_CAPACITY];
        threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private Entry<K, V> find(Object key, int hash) {
        int index = hash & (table.length - 1);
        for (Entry<K, V> e = table[index]; e != null; e = e.next) {
            if (e.hash == hash && (key == e.key || key.equals(e.key))) {
                return e;
            }
        }
        return null;
    }

    public V get(Object key) {
        requireNonNull(key);
        synchronized (lock) {
            Entry<K, V> e = find(key, hash(key));
            return e == null ? null : e.value;
        }
    }

    public boolean containsKey(Object key) {
        requireNonNull(key);
        synchronized (lock) {
            return find(key, hash(key)) != null;
        }
    }

    public V put(K key, V value) {
        return putInternal(key, value, false);
    }

    public V putIfAbsent(K key, V value) {
        return putInternal(key, value, true);
    }

    private V putInternal(K key, V value, boolean onlyIfAbsent) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            int h = hash(key);
            Entry<K, V> existing = find(key, h);
            if (existing != null) {
                V oldValue = existing.value;
                if (!onlyIfAbsent) {
                    existing.value = value;
                }
                return oldValue;
            }

            if (size == 0x7fffffff) {
                throw new IllegalStateException("Map size limit reached");
            }
            if (size >= threshold) {
                grow();
            }

            int index = h & (table.length - 1);
            table[index] = new Entry<K, V>(h, key, value, table[index]);
            size++;
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void grow() {
        int oldCapacity = table.length;
        if (oldCapacity == MAX_CAPACITY) {
            threshold = 0x7fffffff;
            return;
        }

        int newCapacity = oldCapacity << 1;
        Entry<K, V>[] newTable =
                (Entry<K, V>[]) new Entry<?, ?>[newCapacity];

        for (int i = 0; i < oldCapacity; i++) {
            Entry<K, V> e = table[i];
            while (e != null) {
                Entry<K, V> next = e.next;
                int index = e.hash & (newCapacity - 1);
                e.next = newTable[index];
                newTable[index] = e;
                e = next;
            }
        }

        table = newTable;
        threshold = newCapacity == MAX_CAPACITY
                ? 0x7fffffff
                : newCapacity - (newCapacity >>> 2);
    }

    public V remove(Object key) {
        requireNonNull(key);
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> e = table[index];

            while (e != null) {
                if (e.hash == h && (key == e.key || key.equals(e.key))) {
                    if (previous == null) {
                        table[index] = e.next;
                    } else {
                        previous.next = e.next;
                    }
                    size--;
                    return e.value;
                }
                previous = e;
                e = e.next;
            }
            return null;
        }
    }

    public V replace(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            Entry<K, V> e = find(key, hash(key));
            if (e == null) {
                return null;
            }
            V oldValue = e.value;
            e.value = value;
            return oldValue;
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
}
