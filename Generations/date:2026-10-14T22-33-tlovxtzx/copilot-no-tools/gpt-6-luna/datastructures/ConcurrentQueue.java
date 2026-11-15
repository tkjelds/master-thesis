@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting while the queue is full.
     */
    public void enqueue(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the next element, waiting while the queue is empty.
     */
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeHead();
        }
    }

    /**
     * Adds an element immediately, returning false if the queue is full.
     */
    public boolean offer(E element) {
        requireNonNull(element);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the next element immediately, or null if empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeHead();
        }
    }

    /**
     * Returns the next element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    @SuppressWarnings("unchecked")
    private E removeHead() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        lock.notifyAll();
        return element;
    }

    private static void requireNonNull(Object element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }
}
