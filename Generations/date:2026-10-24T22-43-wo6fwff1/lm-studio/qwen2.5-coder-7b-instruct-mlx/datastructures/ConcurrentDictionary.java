```java
public class ConcurrentDictionary {
    private final Object[] buckets;
    private final int capacity;

    public ConcurrentDictionary(int capacity) {
        this.capacity = capacity;
        this.buckets = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            buckets[i] = new Object(); // each bucket is a monitor
        }
    }

    public void put(int key, String value) {
        int index = hash(key);
        synchronized (buckets[index]) {
            // implementation of put logic
        }
    }

    public String get(int key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            // implementation of get logic
        }
    }

    public void remove(int key) {
        int index = hash(key);
        synchronized (buckets[index]) {
            // implementation of remove logic
        }
    }

    private int hash(int key) {
        return Math.abs(key % capacity);
    }
}
```
