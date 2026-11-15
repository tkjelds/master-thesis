@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] buffer;
    private int head;
    private int tail;
    private int count;
    private boolean closed;

    /**
     * Creates a bounded, thread-safe FIFO queue.
     *
     * Policy summary:
     *  - put(e):  blocks while the queue is full; throws IllegalStateException if closed.
     *  - take():  blocks while the queue is empty; returns null once the queue is closed and drained.
     *  - offer(e): non-blocking; returns false if full or closed.
     *  - poll():  non-blocking; returns null if empty.
     *  - close(): wakes all waiters; no further insertions are accepted, but remaining
     *             elements can still be taken.
     *  - Null elements are rejected with NullPointerException.
     */
    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.buffer = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
        this.closed = false;
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
        synchronized (lock) {
            while (count == buffer.length && !closed) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("queue is closed");
            }
            enqueueLocked(element);
            lock.notifyAll();
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
        synchronized (lock) {
            if (closed || count == buffer.length) {
                return false;
            }
            enqueueLocked(element);
            lock.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0 && !closed) {
                lock.wait();
            }
            if (count == 0) {
                // closed and drained
                return null;
            }
            E element = dequeueLocked();
            lock.notifyAll();
            return element;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = dequeueLocked();
            lock.notifyAll();
            return element;
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
            return count == buffer.length;
        }
    }

    public int capacity() {
        return buffer.length;
    }

    public void close() {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                lock.notifyAll();
            }
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    // Must be called while holding lock and after verifying there is space.
    private void enqueueLocked(E element) {
        buffer[tail] = element;
        tail = (tail + 1) % buffer.length;
        count++;
    }

    // Must be called while holding lock and after verifying count > 0.
    @SuppressWarnings("unchecked")
    private E dequeueLocked() {
        Object value = buffer[head];
        buffer[head] = null;
        head = (head + 1) % buffer.length;
        count--;
        return (E) value;
    }
}
