@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            monitor.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (monitor) {
            if (size == elements.length) {
                return false;
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == elements.length;
        }
    }
}
