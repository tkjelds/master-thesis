public class ConcurrentDictionary {

    private static class Entry {
        private final String key;
        private String value;

        public Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private final Entry[] table;
    private int size;
    private final int capacity;

    public ConcurrentDictionary(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.table = new Entry[capacity];
        this.size = 0;
    }

    public void put(String key, String value) throws InterruptedException {
        if (key == null) return;

        synchronized (this.table) {
            int index = Math.abs(key.hashCode() % capacity);
            
            for (int i = 0; i < capacity; i++) {
                int currentIndex = (index + i) % capacity;
                Entry entry = table[currentIndex];

                if (entry != null) {
                    if (entry.key.equals(key)) {
                        entry.value = value;
                        return;
                    }
                } else {
                    // Found an empty slot, but we need to check if key exists elsewhere
                    // In a simple hash table without linked lists/probing, we must ensure 
                    // the key isn't elsewhere. However, to keep it simple and correct:
                    // We check the whole array first or use a linear probe.
                }
            }

            // If we reached here, the key does not exist in this bucket or chain
            // Standard Hash Table logic: Find first null slot or correct key
            for (int i = 0; i < capacity; i++) {
                int idx = (index + i) % capacity;
                Entry e = table[idx];
                if (e == null) {
                    table[idx] = new Entry(key, value);
                    size++;
                    return;
                } else if (e.key.equals(key)) {
                    e.value = value;
                    return;
                }
            }
        }
    }

    public String get(String key) {
        if (key == null) return null;

        synchronized (this.table) {
            int index = Math.abs(key.hashCode() % capacity);
            for (int i = 0; i < capacity; i++) {
                int idx = (index + i) % capacity;
                Entry entry = table[idx];
                if (entry == null) {
                    return null;
                }
                if (entry.key.equals(key)) {
                    return entry.value;
                }
            }
        }
        return null;
    }

    public boolean remove(String key) {
        if (key == null) return false;

        synchronized (this.table) {
            int index = Math.abs(key.hashCode() % capacity);
            for (int i = 0; i < capacity; i++) {
                int idx = (index + i) % capacity;
                Entry entry = table[idx];
                if (entry == null) {
                    return false;
                }
                if (entry.key.equals(key)) {
                    table[idx] = null;
                    size--;
                    return true;
                }
            }
        }
        return false;
    }

    public int size() {
        synchronized (this.table) {
            return size;
        }
    }

    public boolean isFull() {
        synchronized (this.table) {
            return size == capacity;
        }
    }

    public void putIfFull(String key, String value) throws InterruptedException {
        synchronized (this.table) {
            while (size == capacity) {
                // Block until space is available
                this.table.wait();
            }
            put(key, value);
        }
    }

    public void removeIfEmpty() throws InterruptedException {
        synchronized (this.table) {
            while (size == 0) {
                // Block until not empty
                this.table.wait();
            }
            // Logic to actually remove would go here if requested, 
            // but the requirement is just to block until not empty.
        }
    }
}
