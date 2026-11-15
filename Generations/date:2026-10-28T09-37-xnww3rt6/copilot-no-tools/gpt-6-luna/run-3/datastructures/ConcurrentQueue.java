@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        requireNonNull(item);
        synchronized (monitor) {
            while (count == elements.length) {
                monitor.wait();
            }
            enqueue(item);
            monitor.notifyAll();
        }
    }

    public boolean offer(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (count == elements.length) {
                return false;
            }
            enqueue(item);
            monitor.notifyAll();
            return true;
        }
    }

    public T take() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }
            T item = dequeue();
            monitor.notifyAll();
            return item;
        }
    }

    public T poll() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            T item = dequeue();
            monitor.notifyAll();
            return item;
        }
    }

    public T peek() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            T item = (T) elements[head];
            return item;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return elements.length - count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
        }
    }

    private void enqueue(T item) {
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        count++;
    }

    @SuppressWarnings("unchecked")
    private T dequeue() {
        T item = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        return item;
    }

    private void requireNonNull(T item) {
        if (item == null) {
            throw new IllegalArgumentException("null items are not supported");
        }
    }
}
