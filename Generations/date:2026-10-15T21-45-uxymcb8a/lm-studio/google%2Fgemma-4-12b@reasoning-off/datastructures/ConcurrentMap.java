@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static class Entry<K, V> {
        K key;
        V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private Entry<K, V>[] table;
    private int capacity;
    private int size;
    private final Object lock = new Object();

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = initialCapacity;
        this.table = (Entry<K, V>[]) new Entry[capacity];
        this.size = 0;
    }

    private int hash(K key) {
        int h = 0;
        if (key instanceof Integer) {
            h = (int) key;
        } else if (key instanceof Long) {
            h = (int) ((Long) key ^ ((Long) key >>> 32));
        } else {
            h = key.hashCode();
        }
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    public void put(K key, V value) {
        if (key == null) throw new NullPointerException();
        synchronized (lock) {
            int index = hash(key) % capacity;
            for (int i = 0; i < capacity; i++) {
                int pos = (index + i) % capacity;
                Entry<K, V> entry = table[pos];
                if (entry == null) {
                    table[pos] = new Entry<>(key, value);
                    size++;
                    break;
                } else if (entry.key.equals(key)) {
                    entry.value = value;
                    break;
                }
            }
        }
    }

    public V get(K key) {
        if (key == null) return null;
        V result = null;
        synchronized (lock) {
            int index = hash(key) % capacity;
            for (int i = 0; i < capacity; i++) {
                int pos = (index + i) % capacity;
                Entry<K, V> entry = table[pos];
                if (entry == null) {
                    break;
                } else if (entry.key.equals(key)) {
                    result = entry.value;
                    break;
                }
            }
        }
        return result;
    }

    public boolean remove(K key) {
        if (key == null) return false;
        boolean removed = false;
        synchronized (lock) {
            int index = hash(key) % capacity;
            for (int i = 0; i < capacity; i++) {
                int pos = (index + i) % capacity;
                Entry<K, V> entry = table[pos];
                if (entry == null) {
                    break;
                } else if (entry.key.equals(key)) {
                    table[pos] = null;
                    size--;
                    removed = true;
                    break;
                }
            }
        }
        return removed;
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
            for (int i = 0; i < capacity; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }
}
