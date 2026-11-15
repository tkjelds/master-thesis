@ConcurrentBag.ThreadSafe
public class ConcurrentBag<T> {
    @interface ThreadSafe {}

    private final T[] items;
    private int size = 0;

    @SuppressWarnings("unchecked")
    public ConcurrentBag(int capacity) {
        items = (T[]) new Object[capacity];
    }

    public synchronized void add(T item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        items[size++] = item;
        notifyAll();
    }

    public synchronized T remove() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T item = items[--size];
        notifyAll();
        return item;
    }

    public synchronized boolean contains(T item) {
        for (int i = 0; i < size; i++) {
            if (items[i].equals(item)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private boolean isFull() {
        return size == items.length;
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
