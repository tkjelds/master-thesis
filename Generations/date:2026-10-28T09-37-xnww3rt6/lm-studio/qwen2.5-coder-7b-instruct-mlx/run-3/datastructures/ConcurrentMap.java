```java
public class ConcurrentMap<K, V> {
    private static final Object[] EMPTY_ENTRY = new Object[0];
    private Object[][] data;
    private volatile int size;

    public ConcurrentMap() {
        this(16);
    }

    public ConcurrentMap(int initialCapacity) {
        this.data = new Object[initialCapacity][];
    }

    public V put(K key, V value) {
        while (true) {
            int index = hash(key);
            V oldValue;
            synchronized (getEntry(index)) {
                Object[] entry = data[index];
                if (entry == null) {
                    entry = new Object[]{key, value};
                    data[index] = entry;
                    size++;
                    return null;
                } else {
                    oldValue = (V) entry[1];
                    if (entry[0].equals(key)) {
                        return oldValue;
                    }
                }
            }
        }
    }

    public V get(K key) {
        int index = hash(key);
        V value;
        synchronized (getEntry(index)) {
            Object[] entry = data[index];
            if (entry != null && entry[0].equals(key)) {
                value = (V) entry[1];
            } else {
                return null;
            }
        }
        return value;
    }

    public V remove(K key) {
        while (true) {
            int index = hash(key);
            V oldValue;
            synchronized (getEntry(index)) {
                Object[] entry = data[index];
                if (entry != null) {
                    if (entry[0].equals(key)) {
                        oldValue = (V) entry[1];
                        data[index] = null;
                        size--;
                        return oldValue;
                    }
                } else {
                    return null;
                }
            }
        }
    }

    public int size() {
        return size;
    }

    private Object getEntry(int index) {
        if (index >= data.length) {
            return EMPTY_ENTRY;
        }
        return data[index] != null ? data[index] : EMPTY_ENTRY;
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % data.length;
    }
}
```
