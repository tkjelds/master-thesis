@ConcurrentCollection.ThreadSafe
public class ConcurrentCollection<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size = 0;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        elements = new Object[capacity];
    }

    public synchronized boolean add(T element) {
        if (size == elements.length) {
            throw new IllegalStateException("Collection is full");
        }
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized T remove() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted", e);
            }
        }
        T element = (T) elements[--size];
        notifyAll();
        return element;
    }

    public synchronized T peek() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted", e);
            }
        }
        return (T) elements[size - 1];
    }

    public synchronized int size() {
        return size;
    }
}
