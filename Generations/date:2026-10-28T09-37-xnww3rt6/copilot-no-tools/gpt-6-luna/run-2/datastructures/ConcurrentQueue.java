@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element must not be null");
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

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            Object element = elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();

            @SuppressWarnings("unchecked")
            E result = (E) element;
            return result;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
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

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            Object element = elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();

            @SuppressWarnings("unchecked")
            E result = (E) element;
            return result;
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
