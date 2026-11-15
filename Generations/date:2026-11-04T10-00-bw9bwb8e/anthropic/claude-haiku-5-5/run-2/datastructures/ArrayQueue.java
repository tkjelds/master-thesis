@ArrayQueue.ThreadSafe
public class ArrayQueue<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;
    private int tail;
    private int count;

    /**
     * Creates a bounded FIFO queue with the given fixed capacity.
     *
     * @param capacity maximum number of elements; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Blocking insert. Waits while the queue is full.
     * Null elements are rejected.
     *
     * @throws NullPointerException if item is null
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            enqueueLocked(item);
            lock.notifyAll();
        }
    }

    /**
     * Blocking removal. Waits while the queue is empty.
     *
     * @return the head element
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T item = dequeueLocked();
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Non-blocking insert.
     *
     * @return true if the element was added; false if the queue was full
     * @throws NullPointerException if item is null
     */
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            enqueueLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Non-blocking removal.
     *
     * @return the head element, or null if the queue was empty
     */
    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T item = dequeueLocked();
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Returns the head element without removing it.
     *
     * @return the head element, or null if the queue was empty
     */
    @SuppressWarnings("unchecked")
    public T peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (T) items[head];
        }
    }

    /** @return the current number of elements */
    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    /** @return true if the queue currently holds no elements */
    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    /** @return true if the queue is currently at capacity */
    public boolean isFull() {
        synchronized (lock) {
            return count == items.length;
        }
    }

    /** @return the fixed capacity of this queue */
    public int capacity() {
        return items.length;
    }

    /** Must be called while holding {@code lock} and when there is free space. */
    private void enqueueLocked(T item) {
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
    }

    /** Must be called while holding {@code lock} and when the queue is non-empty. */
    @SuppressWarnings("unchecked")
    private T dequeueLocked() {
        T item = (T) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return item;
    }
}
