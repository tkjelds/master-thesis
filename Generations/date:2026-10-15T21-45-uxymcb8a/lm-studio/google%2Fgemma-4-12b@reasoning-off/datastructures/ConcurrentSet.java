@ConcurrentSet.ThreadSafe
public class ConcurrentSet<T extends Comparable<T>> {
    @interface ThreadSafe {}


    private String[] elements;
    private int size;
    private final int capacity;

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = initialCapacity;
        this.elements = new String[initialCapacity];
        this.size = 0;
    }

    // Using a simple internal class to handle generic comparison and storage.
    // Since we cannot use java.util types, we rely on raw arrays or manual handling.
    // For a generic set without library helpers, we wrap the elements.
    private final Object[] data;

    public ConcurrentSet(int capacity) {
        this.capacity = capacity;
        this.data = new Object[capacity];
        this.size = 0;
    }

    public boolean add(Object element) {
        synchronized (this) {
            if (contains(element)) {
                return false;
            }

            while (size == capacity) {
                try {
                    synchronized (this) {
                        this.wait();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            data[size] = element;
            size++;
            return true;
        }
    }

    public boolean remove(Object element) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (data[i] == null || data[i].equals(element)) {
                    data[i] = null;
                    // Shift elements to maintain contiguous array for simplicity
                    for (int j = i; j < size - 1; j++) {
                        data[j] = data[j + 1];
                    }
                    size--;
                    notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object element) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (data[i] != null && data[i].equals(element)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean remove(Object element, java.util.function.Consumer<Object> action) {
        // Custom behavior for removal not required by prompt, but standard logic follows.
        return false; 
    }

    // Overloaded add with notification logic to satisfy 'blocking' requirement properly.
    public void add_blocking(Object element) throws InterruptedException {
        synchronized (this) {
            while (size == capacity && !contains(element)) {
                this.wait();
            }
            if (!contains(element)) {
                data[size] = element;
                size++;
            }
        }
    }

    // Standard method following strict requirements: 
    // Implements a thread-safe set where add blocks when full.
    public boolean addWithWait(Object element) throws InterruptedException {
        synchronized (this) {
            if (contains(element)) {
                return false;
            }
            while (size == capacity) {
                this.wait();
            }
            data[size] = element;
            size++;
            return true;
        }
    }

    // Helper to allow removing/notifying in a standard way.
    public void removeAndNotify(Object element) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (data[i] != null && data[i].equals(element)) {
                    data[i] = null;
                    for (int j = i; j < size - 1; j++) {
                        data[j] = data[j + 1];
                    }
                    size--;
                    notifyAll();
                    return;
                }
            }
        }
    }

    // Re-implementing the core requested class structure with clean logic.
    private static class SetImpl<T> {
        private Object[] items;
        private int count;

        public SetImpl(int cap) {
            items = new Object[cap];
        }

        public synchronized boolean add(T val) throws InterruptedException {
            if (val == null) return false;
            if (contains(val)) return false;
            while (count == items.length) {
                wait();
            }
            items[count++] = val;
            return true;
        }

        public synchronized boolean remove(T val) {
            for (int i = 0; i < count; i++) {
                if (items[i] != null && items[i].equals(val)) {
                    items[i] = null;
                    for (int j = i; j < count - 1; j++) {
                        items[j] = items[j + 1];
                    }
                    count--;
                    notifyAll();
                    return true;
                }
            }
            return false;
        }

        public synchronized boolean contains(T val) {
            for (int i = 0; i < count; i++) {
                if (items[i] != null && items[i].equals(val)) return true;
            }
            return false;
        }

        public synchronized int size() { return count; }
    }
}

// Note: The class above serves as a reference. 
// Following the requested structure precisely for the final implementation:

@ThreadSafeSet.ThreadSafe
class ThreadSafeSet<T> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;
    private final int capacity;

    public ThreadSafeSet(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public boolean add(T element) throws InterruptedException {
        synchronized (this) {
            if (element == null) return false;
            if (contains(element)) return false;

            while (size == capacity) {
                this.wait();
            }

            elements[size] = element;
            size++;
            return true;
        }
    }

    public boolean remove(T element) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (elements[i] != null && elements[i].equals(element)) {
                    elements[i] = null;
                    for (int j = i; j < size - 1; j++) {
                        elements[j] = elements[j + 1];
                    }
                    size--;
                    notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(T element) {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (elements[i] != null && elements[i].equals(element)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }
}
