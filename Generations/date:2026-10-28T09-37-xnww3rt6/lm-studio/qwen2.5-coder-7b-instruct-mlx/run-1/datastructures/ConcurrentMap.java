@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Object[][] buckets;
    private final int capacity;
    private final float loadFactor;

    public ConcurrentMap(int initialCapacity, float loadFactor) {
        if (initialCapacity <= 0) throw new IllegalArgumentException("Initial capacity must be positive");
        if (loadFactor <= 0) throw new IllegalArgumentException("Load factor must be positive");

        this.capacity = (int) Math.ceil(initialCapacity / loadFactor);
        buckets = new Object[this.capacity][];
        this.loadFactor = loadFactor;
    }

    public V put(K key, V value) {
        return doPut(key, value);
    }

    public V get(K key) {
        int index = getIndex(key);
        synchronized (buckets[index]) {
            for (Object[] entry : buckets[index]) {
                if (entry != null && ((Map.Entry<K, V>) entry).getKey().equals(key)) {
                    return (V) ((Map.Entry<K, V>) entry).getValue();
                }
            }
        }
        return null;
    }

    public V remove(K key) {
        int index = getIndex(key);
        synchronized (buckets[index]) {
            for (int i = 0; i < buckets[index].length; i++) {
                if (buckets[index][i] != null && ((Map.Entry<K, V>) buckets[index][i]).getKey().equals(key)) {
                    V oldValue = (V) ((Map.Entry<K, V>) buckets[index][i]).getValue();
                    buckets[index][i] = null;
                    return oldValue;
                }
            }
        }
        return null;
    }

    public boolean containsKey(K key) {
        int index = getIndex(key);
        synchronized (buckets[index]) {
            for (Object entry : buckets[index]) {
                if (entry != null && ((Map.Entry<K, V>) entry).getKey().equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean containsValue(V value) {
        for (Object[] bucket : buckets) {
            synchronized (bucket) {
                for (Object entry : bucket) {
                    if (entry != null && ((Map.Entry<K, V>) entry).getValue().equals(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public int size() {
        int count = 0;
        for (Object[] bucket : buckets) {
            synchronized (bucket) {
                for (Object entry : bucket) {
                    if (entry != null) count++;
                }
            }
        }
        return count;
    }

    public boolean isEmpty() {
        for (Object[] bucket : buckets) {
            synchronized (bucket) {
                if (bucket.length > 0) return false;
            }
        }
        return true;
    }

    private int getIndex(K key) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        return Math.abs(key.hashCode()) % capacity;
    }

    private V doPut(K key, V value) {
        int index = getIndex(key);
        synchronized (buckets[index]) {
            for (int i = 0; i < buckets[index].length; i++) {
                if (buckets[index][i] != null && ((Map.Entry<K, V>) buckets[index][i]).getKey().equals(key)) {
                    return putIfAbsent(index, i, key, value);
                }
            }
            buckets[index] = appendEntry(buckets[index], new SimpleEntry<>(key, value));
        }
        return null;
    }

    private V putIfAbsent(int index, int i, K key, V value) {
        if (buckets[index][i] != null && ((Map.Entry<K, V>) buckets[index][i]).getValue() == value) {
            return value;
        } else {
            V oldValue = (V) ((Map.Entry<K, V>) buckets[index][i]).getValue();
            buckets[index][i] = new SimpleEntry<>(key, value);
            return oldValue;
        }
    }

    private Object[] appendEntry(Object[] entries, Map.Entry<K, V> entry) {
        int length = entries.length;
        Object[] newEntries = new Object[length + 1];
        System.arraycopy(entries, 0, newEntries, 0, length);
        newEntries[length] = entry;
        return newEntries;
    }

    private static class SimpleEntry<K, V> implements Map.Entry<K, V> {
        private K key;
        private V value;

        public SimpleEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V newValue) {
            V oldValue = value;
            value = newValue;
            return oldValue;
        }
    }
}
