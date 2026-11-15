@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting while the queue is full.
     * Null elements are not permitted.
     */
    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("Null elements are not permitted");
        }

        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the next element, waiting while the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }
}
