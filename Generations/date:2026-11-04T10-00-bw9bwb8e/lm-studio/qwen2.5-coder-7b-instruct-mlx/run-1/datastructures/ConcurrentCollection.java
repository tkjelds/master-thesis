@ConcurrentCollection.ThreadSafe
public class ConcurrentCollection<T> {
    @interface ThreadSafe {}

    private final Object[] items;
    private int size = 0;
    private final int capacity;

    public ConcurrentCollection(int capacity) {
        this.capacity = capacity;
        items = new Object[capacity];
    }

    public synchronized boolean add(T item) throws InterruptedException {
        while (size == capacity) {
            wait();
        }
        if (item != null) {
            items[size++] = item;
            notifyAll();
        }
        return true;
    }

    public synchronized T remove() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T item = null;
        if (size > 0) {
            item = (T) items[--size];
            notifyAll();
        }
        return item;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
