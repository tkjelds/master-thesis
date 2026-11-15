@ConcurrentList.ThreadSafe
public class ConcurrentList<T> {
    @interface ThreadSafe {}


    private Object[] elements;
    private int size;
    private final int capacity;

    public ConcurrentList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public void add(T item) throws InterruptedException {
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null");
        }
        synchronized (this) {
            while (size == capacity) {
                (this).wait();
            }
            elements[size] = item;
            size++;
            (this).notifyAll();
        }
    }

    public T remove() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                (this).wait();
            }
            T item = (T) elements[--size];
            elements[size] = null; // Clear reference for GC
            (this).notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == capacity;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            return false;
        }
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            return false;
        }
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    elements[i] = elements[size - 1];
                    elements[size - 1] = null;
                    size--;
                    (this).notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public int getIndex(T item) {
        if (item == null) {
            return -1;
        }
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }
    }
}
