@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final Object monitor = new Object();
    private int head;
    private int tail;
    private int size;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(T element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
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

    public T take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            T element = elementAt(head);
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public boolean offer(T element) {
        if (element == null) {
            throw new NullPointerException("element");
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

    public T poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            T element = elementAt(head);
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

    @SuppressWarnings("unchecked")
    private T elementAt(int index) {
        return (T) elements[index];
    }
}
