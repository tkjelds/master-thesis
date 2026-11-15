@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private final Object[] buckets;
    private final int capacity;
    private volatile int size;

    public ConcurrentDictionary(int capacity) {
        this.capacity = capacity;
        this.buckets = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Object();
        }
        this.size = 0;
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        int index = hash(key);
        synchronized (buckets[index]) {
            return (V) ((HashMap<K, V>) buckets[index]).get(key);
        }
    }

    public void put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and Value cannot be null");
        }
        int index = hash(key);
        synchronized (buckets[index]) {
            HashMap<K, V> map = (HashMap<K, V>) buckets[index];
            if (!map.containsKey(key)) {
                size++;
            }
            map.put(key, value);
        }
    }

    public void remove(K key) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }
        int index = hash(key);
        synchronized (buckets[index]) {
            HashMap<K, V> map = (HashMap<K, V>) buckets[index];
            if (map.containsKey(key)) {
                size--;
                map.remove(key);
            }
        }
    }

    public int size() {
        return size;
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % capacity;
    }
}
