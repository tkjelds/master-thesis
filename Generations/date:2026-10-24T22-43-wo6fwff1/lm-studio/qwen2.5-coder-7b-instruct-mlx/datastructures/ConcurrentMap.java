```java
public class ConcurrentMap<K, V> {
    private final Object[] locks;
    private final Object[] values;

    public ConcurrentMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        locks = new Object[capacity];
        values = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            locks[i] = new Object();
            values[i] = null;
        }
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Key and value must not be null");
        }
        int index = key.hashCode() % locks.length;
        synchronized (locks[index]) {
            V oldValue = (V) values[index];
            values[index] = value;
            return oldValue;
        }
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        int index = key.hashCode() % locks.length;
        synchronized (locks[index]) {
            return (V) values[index];
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        int index = key.hashCode() % locks.length;
        synchronized (locks[index]) {
            V oldValue = (V) values[index];
            values[index] = null;
            return oldValue;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }
        int index = key.hashCode() % locks.length;
        synchronized (locks[index]) {
            return values[index] != null;
        }
    }

    public boolean containsValue(V value) {
        if (value == null) {
            throw new NullPointerException("Value must not be null");
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && values[i].equals(value)) {
                synchronized (locks[i]) {
                    if (values[i] != null && values[i].equals(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void clear() {
        for (int i = 0; i < values.length; i++) {
            synchronized (locks[i]) {
                values[i] = null;
            }
        }
    }

    public int size() {
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                synchronized (locks[i]) {
                    if (values[i] != null) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
```
