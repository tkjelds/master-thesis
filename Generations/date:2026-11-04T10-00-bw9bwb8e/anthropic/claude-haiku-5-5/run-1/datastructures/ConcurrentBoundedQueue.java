@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<T> {
    @interface ThreadSafe {}


    /*
     * Policy summary:
     *  - put(item): blocks while the queue is full; throws IllegalStateException if closed.
     *  - take(): blocks while the queue is empty; returns null only when the queue is
     *    closed and fully drained.
     *  - offer(item): non-blocking; returns false if the queue is full or closed.
     *  - poll(): non-blocking; returns null if the queue is empty.
     *  - close(): no further elements may be added; waiting producers are released with
     *    an IllegalStateException, and waiting consumers drain remaining elements and
     *    then receive null.
     *  - Null elements are rejected with NullPointerException.
     *
     * All state is guarded by a single private monitor. Every wait is guarded by a
     * while loop, and every state change that could unblock a waiter calls notifyAll.
     */

    private final Object lock = new Object();
    private final Object[] items;
    private final int capacity;
    private int head;
    private int tail;
    private int count;
    private boolean closed;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (count == capacity && !closed) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("queue is closed");
            }
            enqueueLocked(item);
            lock.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0 && !closed) {
                lock.wait();
            }
            if (count == 0) {
                // Closed and drained.
                return null;
            }
            T item = dequeueLocked();
            lock.notifyAll();
            return item;
        }
    }

    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (closed || count == capacity) {
                return false;
            }
            enqueueLocked(item);
            lock.notifyAll();
            return true;
        }
    }

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

    public int capacity() {
        return capacity;
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public void close() {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                lock.notifyAll();
            }
        }
    }

    // Must be called while holding lock and when count < capacity.
    private void enqueueLocked(T item) {
        items[tail] = item;
        tail = (tail + 1) % capacity;
        count++;
    }

    // Must be called while holding lock and when count > 0.
    @SuppressWarnings("unchecked")
    private T dequeueLocked() {
        Object value = items[head];
        items[head] = null;
        head = (head + 1) % capacity;
        count--;
        return (T) value;
    }
}
