@ThreadSafeDictionary.ThreadSafe
public class ThreadSafeDictionary {
    @interface ThreadSafe {}

    private final Object[] entries;
    private final int capacity;
    private int size;

    public ThreadSafeDictionary(int capacity) {
        this.capacity = capacity;
        this.entries = new Object[capacity];
        this.size = 0;
    }

    public boolean put(int key, String value) {
        synchronized (this) {
            while (containsKey(key)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (size == capacity) {
                return false; // Dictionary is full
            }

            entries[key % capacity] = value;
            size++;
            notifyAll();
            return true;
        }
    }

    public String get(int key) {
        synchronized (this) {
            while (!containsKey(key)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            notifyAll();
            return (String) entries[key % capacity];
        }
    }

    public boolean remove(int key) {
        synchronized (this) {
            while (!containsKey(key)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            entries[key % capacity] = null;
            size--;
            notifyAll();
            return true;
        }
    }

    private boolean containsKey(int key) {
        return entries[key % capacity] != null;
    }
}
