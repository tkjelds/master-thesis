@ConcurrentCollection.ThreadSafe
public class ConcurrentCollection {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size = 0;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        elements = new Object[capacity];
    }

    public synchronized void add(Object element) {
        if (isFull()) {
            waitUntilNotFull();
        }
        elements[size++] = element;
    }

    public synchronized Object remove() {
        if (isEmpty()) {
            waitUntilNotEmpty();
        }
        Object element = elements[0];
        System.arraycopy(elements, 1, elements, 0, --size);
        return element;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isFull() {
        return size == elements.length;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    private void waitUntilNotFull() {
        try {
            while (isFull()) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitUntilNotEmpty() {
        try {
            while (isEmpty()) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void notifyAllWaiters() {
        notifyAll();
    }
}
