@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Object[] buckets;
    private final int capacity;

    public ConcurrentMap(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        buckets = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Object();
        }
    }

    public V get(K key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            return findKey(index, key);
        }
    }

    public V put(K key, V value) {
        int index = hash(key);
        synchronized (buckets[index]) {
            return putKey(index, key, value);
        }
    }

    public V remove(K key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            return removeKey(index, key);
        }
    }

    private int hash(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        return Math.abs(key.hashCode()) % capacity;
    }

    private V findKey(int index, K key) {
        for (MapEntry<K, V> entry = (MapEntry<K, V>) buckets[index]; entry != null; entry = entry.next) {
            if (entry.key.equals(key)) {
                return entry.value;
            }
        }
        return null;
    }

    private V putKey(int index, K key, V value) {
        for (MapEntry<K, V> entry = (MapEntry<K, V>) buckets[index]; entry != null; entry = entry.next) {
            if (entry.key.equals(key)) {
                V oldValue = entry.value;
                entry.value = value;
                return oldValue;
            }
        }
        buckets[index] = new MapEntry<>(key, value, (MapEntry<K, V>) buckets[index]);
        return null;
    }

    private V removeKey(int index, K key) {
        MapEntry<K, V> dummy = new MapEntry<>(null, null, null);
        buckets[index] = dummy;
        for (MapEntry<K, V> prev = dummy; ; prev = prev.next) {
            MapEntry<K, V> entry = prev.next;
            if (entry == null) break;
            if (entry.key.equals(key)) {
                prev.next = entry.next;
                return entry.value;
            }
        }
        throw new NullPointerException("Key not found");
    }

    private static class MapEntry<K, V> {
        final K key;
        V value;
        MapEntry<K, V> next;

        MapEntry(K key, V value, MapEntry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
