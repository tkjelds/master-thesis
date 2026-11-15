@ConcurrentBoundedCollection.ThreadSafe
public final class ConcurrentBoundedCollection<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    /**
     * Creates a thread-safe bounded FIFO collection.
     *
     * Policy:
     * - put()/take() block (wait/notifyAll) until space/elements are available.
     * - offer()/poll() never block; they return false/null immediately.
     * - offer(item, timeout)/poll(timeout) block at most the given time.
     * - Blocking operations throw InterruptedException if the calling thread is interrupted;
     *   the collection state is left unchanged in that case.
     * - Null elements are rejected with NullPointerException.
     */
    public ConcurrentBoundedCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /** Blocks until space is available, then inserts the item. */
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            insertLocked(item);
            lock.notifyAll();
        }
    }

    /** Blocks until an element is available, then removes and returns it. */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T result = (T) removeLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Non-blocking insert. Returns false if the collection is full. */
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            insertLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Non-blocking removal. Returns null if the collection is empty. */
    @SuppressWarnings("unchecked")
    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T result = (T) removeLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Inserts the item, waiting at most timeoutMillis for space.
     * Returns false if the timeout expires first.
     */
    public boolean offer(T item, long timeoutMillis) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (count == capacity) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                lock.wait(ceilMillis(remainingNanos));
            }
            insertLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes an element, waiting at most timeoutMillis for one to become available.
     * Returns null if the timeout expires first.
     */
    @SuppressWarnings("unchecked")
    public T poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (count == 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                lock.wait(ceilMillis(remainingNanos));
            }
            T result = (T) removeLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Returns the current number of elements. */
    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    /** Returns the fixed capacity. */
    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == capacity;
        }
    }

    /** Returns true if the collection contains an element equal to the given object. */
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            int idx = head;
            for (int i = 0; i < count; i++) {
                if (o.equals(items[idx])) {
                    return true;
                }
                idx = (idx + 1) % capacity;
            }
            return false;
        }
    }

    /** Removes all elements and wakes any blocked producers. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                items[i] = null;
            }
            head = 0;
            tail = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of the current elements in FIFO order. */
    public Object[] snapshot() {
        synchronized (lock) {
            Object[] copy = new Object[count];
            int idx = head;
            for (int i = 0; i < count; i++) {
                copy[i] = items[idx];
                idx = (idx + 1) % capacity;
            }
            return copy;
        }
    }

    // Must be called while holding lock and when count < capacity.
    private void insertLocked(T item) {
        items[tail] = item;
        tail = (tail + 1) % capacity;
        count++;
    }

    // Must be called while holding lock and when count > 0.
    private Object removeLocked() {
        Object result = items[head];
        items[head] = null;
        head = (head + 1) % capacity;
        count--;
        return result;
    }

    private static long ceilMillis(long nanos) {
        long ms = (nanos + 999_999L) / 1_000_000L;
        return ms <= 0 ? 1L : ms;
    }
}
