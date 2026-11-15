@ConcurrentDictionaryBag.ThreadSafe
public class ConcurrentDictionaryBag<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static class Entry<K, V> {
        K key;
        V value;
        int count;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
            this.count = 1;
        }
    }

    private final Entry<K, V>[] table;
    private int size;
    private final Object lock = new Object();

    @SuppressWarnings("unchecked")
    public ConcurrentDictionaryBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.table = (Entry[]) new Entry[capacity];
        this.size = 0;
    }

    public void put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null.");
        }
        synchronized (lock) {
            int index = Math.abs(key.hashCode() % table.length);
            Entry<K, V> entry = table[index];

            if (entry != null && key.equals(entry.key)) {
                // Replace value or increment count? 
                // Standard DictionaryBag behavior: if key exists, update/increment.
                entry.count++;
                // Note: In a Bag, if value is updated, it replaces the old mapping. 
                // If we strictly treat it as a multi-map, we'd store multiple values.
                // Given the prompt "DictionaryBag", it implies a Map-like structure 
                // where values are associated with keys, but we handle duplicates.
            } else {
                // Simple collision handling or null slot check
                // To keep it simple and correct without complex hashing, 
                // we use a basic linear probing or just assume unique index for this example.
                // To ensure thread safety and correct mapping, let's use a simple approach:
                // If it's a basic dictionary bag entry, we check if the slot is null.
                if (table[index] == null) {
                    table[index] = new Entry<>(key, value);
                    size++;
                } else {
                    // Simple handling for collision: find next slot
                    int i = (index + 1) % table.length;
                    while (table[i] != null && !table[i].key.equals(key)) {
                        i = (i + 1) % table.length;
                    }
                    if (table[i] == null) {
                        table[i] = new Entry<>(key, value);
                        size++;
                    } else if (table[i].key.equals(key)) {
                        table[i].count++;
                    }
                }
            }
        }
    }

    public int getCount(K key) {
        if (key == null) return 0;
        int count = 0;
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null && entry.key.equals(key)) {
                    count += entry.count;
                }
            }
        }
        return count;
    }

    public void remove(K key) {
        if (key == null) return;
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null && entry.key.equals(key)) {
                    if (entry.count > 1) {
                        entry.count--;
                    } else {
                        table[i] = null;
                        size--;
                    }
                    break;
                }
            }
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean contains(K key) {
        if (key == null) return false;
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                Entry<K, V> entry = table[i];
                if (entry != null && entry.key.equals(key)) {
                    return true;
                }
            }
        }
        return false;
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
