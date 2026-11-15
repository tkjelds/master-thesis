@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting while the queue is full.
     * Null elements are not permitted.
     */
    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the next element, waiting while the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
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

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }
}
