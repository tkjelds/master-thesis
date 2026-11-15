@ThreadSafeDictionary.ThreadSafe
public class ThreadSafeDictionary<K, V> {
    @interface ThreadSafe {}

    private final Object[] buckets;
    private final int capacity;

    public ThreadSafeDictionary(int capacity) {
        this.capacity = capacity;
        this.buckets = new Object[capacity];
    }

    public void put(K key, V value) {
        int index = hash(key);
        synchronized (buckets[index]) {
            buckets[index] = value;
        }
    }

    public V get(K key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            return (V) buckets[index];
        }
    }

    public boolean containsKey(K key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            return buckets[index] != null;
        }
    }

    public void remove(K key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            buckets[index] = null;
        }
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % capacity;
    }
}
