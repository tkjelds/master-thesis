/**
 * A monitor-protected hash dictionary.
 *
 * Keys and values must be non-null. A null return from get, put, or remove
 * indicates that the key was absent. Keys must maintain stable hash codes and
 * equality while stored, and their hashCode and equals methods must not modify
 * this dictionary.
 *
 * Operations do not wait for entries or capacity; they only acquire the monitor.
 * Inserting a new entry at the maximum representable size throws
 * IllegalStateException. Stored objects themselves are not protected from
 * external mutation.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

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

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary() {
        table = (Entry<K, V>[]) new Entry<?, ?>[INITIAL_CAPACITY];
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            for (Entry<K, V> e = table[index]; e != null; e = e.next) {
                if (e.hash == h && key.equals(e.key)) {
                    return e.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            for (Entry<K, V> e = table[index]; e != null; e = e.next) {
                if (e.hash == h && key.equals(e.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            for (Entry<K, V> e = table[index]; e != null; e = e.next) {
                if (e.hash == h && key.equals(e.key)) {
                    V previous = e.value;
                    e.value = value;
                    return previous;
                }
            }

            if (size == 0x7fffffff) {
                throw new IllegalStateException("dictionary is full");
            }
            if (size >= table.length - (table.length >>> 2)
                    && table.length < MAX_CAPACITY) {
                resize();
                index = h & (table.length - 1);
            }

            table[index] = new Entry<K, V>(h, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        synchronized (lock) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Entry<K, V> previous = null;
            Entry<K, V> current = table[index];

            while (current != null) {
                if (current.hash == h && key.equals(current.key)) {
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

    // Called only while holding lock.
    @SuppressWarnings("unchecked")
    private void resize() {
        Entry<K, V>[] newTable =
                (Entry<K, V>[]) new Entry<?, ?>[table.length << 1];

        for (int i = 0; i < table.length; i++) {
            Entry<K, V> e = table[i];
            while (e != null) {
                Entry<K, V> next = e.next;
                int index = e.hash & (newTable.length - 1);
                e.next = newTable[index];
                newTable[index] = e;
                e = next;
            }
        }
        table = newTable;
    }
}
