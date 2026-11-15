@ThreadSafeDictionary.ThreadSafe
public class ThreadSafeDictionary {
    @interface ThreadSafe {}

    private final String[] keys;
    private final Object[] values;
    private final int capacity;

    public ThreadSafeDictionary(int capacity) {
        this.capacity = capacity;
        this.keys = new String[capacity];
        this.values = new Object[capacity];
    }

    public synchronized boolean insert(String key, Object value) {
        int index = findIndex(key);
        if (index >= 0) {
            return false; // Key already exists
        }
        index = findFreeIndex();
        if (index < 0) {
            return false; // Dictionary is full
        }
        keys[index] = key;
        values[index] = value;
        return true;
    }

    public synchronized Object lookup(String key) {
        int index = findIndex(key);
        if (index < 0) {
            return null; // Key not found
        }
        return values[index];
    }

    public synchronized boolean delete(String key) {
        int index = findIndex(key);
        if (index < 0) {
            return false; // Key not found
        }
        keys[index] = null;
        values[index] = null;
        return true;
    }

    private int findIndex(String key) {
        for (int i = 0; i < capacity; i++) {
            if (keys[i] != null && keys[i].equals(key)) {
                return i;
            }
        }
        return -1; // Key not found
    }

    private int findFreeIndex() {
        for (int i = 0; i < capacity; i++) {
            if (keys[i] == null) {
                return i;
            }
        }
        return -1; // Dictionary is full
    }

    public synchronized int size() {
        int count = 0;
        for (String key : keys) {
            if (key != null) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean isEmpty() {
        return size() == 0;
    }
}
