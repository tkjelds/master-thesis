@ArrayQueue.ThreadSafe
public class ArrayQueue<T> {
    @interface ThreadSafe {}


    private final Object[] items;
    private final Object lock = new Object();
    private int head;
    private int tail;
    private int count;

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
     * Throws NullPointerException for null elements and InterruptedException if interrupted while waiting.
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
     * Throws InterruptedException if interrupted while waiting.
     */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Non-blocking insert. Returns false immediately if the queue is full.
     * Throws NullPointerException for null elements.
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
     * Non-blocking removal. Returns null immediately if the queue is empty.
     */
    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Returns the head element without removing it, or null if empty.
     */
    public T peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return castItem(items[head]);
        }
    }

    /**
     * Removes all elements and wakes any threads waiting for space.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < items.length; i++) {
                items[i] = null;
            }
            head = 0;
            tail = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == items.length;
        }
    }

    /**
     * Fixed capacity of the queue; the array length never changes after construction.
     */
    public int capacity() {
        return items.length;
    }

    // Must be called while holding lock and only when count < items.length.
    private void enqueueLocked(T item) {
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
    }

    // Must be called while holding lock and only when count > 0.
    private T dequeueLocked() {
        Object value = items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return castItem(value);
    }

    @SuppressWarnings("unchecked")
    private T castItem(Object value) {
        return (T) value;
    }
}
