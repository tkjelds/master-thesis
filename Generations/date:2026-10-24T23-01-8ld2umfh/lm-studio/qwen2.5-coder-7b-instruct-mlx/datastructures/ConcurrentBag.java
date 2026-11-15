@ConcurrentBag.ThreadSafe
public class ConcurrentBag {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size;
    private static final int DEFAULT_CAPACITY = 10;

    public ConcurrentBag() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentBag(int capacity) {
        elements = new Object[capacity];
        size = 0;
    }

    public synchronized boolean add(Object element) {
        if (isFull()) {
            waitUntilNotFull();
        }
        elements[size++] = element;
        notifyAll();
        return true;
    }

    public synchronized Object remove() {
        if (isEmpty()) {
            waitUntilNotEmpty();
        }
        Object element = elements[--size];
        notifyAll();
        return element;
    }

    public synchronized boolean contains(Object element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized boolean isFull() {
        return size == elements.length;
    }

    private synchronized boolean isEmpty() {
        return size == 0;
    }

    private void waitUntilNotFull() {
        try {
            while (isFull()) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void waitUntilNotEmpty() {
        try {
            while (isEmpty()) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
