/**
 * A thread-safe, bounded, double-ended queue (deque).
 *
 * Concurrency policy:
 *  - All state (buffer, head index, size) is accessed only while holding the
 *    intrinsic monitor of {@code lock}, which provides mutual exclusion and
 *    happens-before visibility between threads.
 *  - Blocking operations (putFirst, putLast, takeFirst, takeLast) wait when the
 *    deque is full (puts) or empty (takes). Waits are guarded by while loops and
 *    re-check the condition after every wakeup (spurious wakeups are tolerated).
 *  - Every successful state change calls notifyAll(), because both producers and
 *    consumers wait on the same monitor and a notify() could wake the wrong kind
 *    of waiter.
 *  - Non-blocking operations (offerFirst, offerLast, pollFirst, pollLast) never
 *    wait; they return false / null immediately when they cannot complete.
 *  - Null elements are rejected with NullPointerException, since null is used as
 *    the "empty" signal by poll operations.
 *  - If a blocked thread is interrupted, the InterruptedException is propagated
 *    to the caller and the deque is left unchanged (the check and the mutation
 *    are never separated by a wait).
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] buffer;
    private int head;   // index of the first element in the circular buffer
    private int size;   // number of elements currently stored

    /**
     * Creates a bounded deque.
     *
     * @param capacity maximum number of elements; must be positive
     */
    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.buffer = new Object[capacity];
        this.head = 0;
        this.size = 0;
    }

    /** Returns the maximum number of elements this deque can hold. */
    public int capacity() {
        return buffer.length; // immutable after construction; no lock required
    }

    /** Returns the current number of elements. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the deque currently contains no elements. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Inserts at the front, blocking while the deque is full.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void putFirst(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == buffer.length) {
                lock.wait();
            }
            head = (head - 1 + buffer.length) % buffer.length;
            buffer[head] = element;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Inserts at the back, blocking while the deque is full.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void putLast(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == buffer.length) {
                lock.wait();
            }
            int index = (head + size) % buffer.length;
            buffer[index] = element;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the front element, blocking while the deque is empty.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @SuppressWarnings("unchecked")
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % buffer.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Removes and returns the back element, blocking while the deque is empty.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @SuppressWarnings("unchecked")
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            int index = (head + size - 1) % buffer.length;
            E element = (E) buffer[index];
            buffer[index] = null;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Attempts to insert at the front without blocking.
     *
     * @return true if inserted, false if the deque was full
     */
    public boolean offerFirst(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == buffer.length) {
                return false;
            }
            head = (head - 1 + buffer.length) % buffer.length;
            buffer[head] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Attempts to insert at the back without blocking.
     *
     * @return true if inserted, false if the deque was full
     */
    public boolean offerLast(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == buffer.length) {
                return false;
            }
            int index = (head + size) % buffer.length;
            buffer[index] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the front element without blocking.
     *
     * @return the front element, or null if the deque was empty
     */
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = (E) buffer[head];
            buffer[head] = null;
            head = (head + 1) % buffer.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Removes and returns the back element without blocking.
     *
     * @return the back element, or null if the deque was empty
     */
    @SuppressWarnings("unchecked")
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = (head + size - 1) % buffer.length;
            E element = (E) buffer[index];
            buffer[index] = null;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the front element without removing it.
     *
     * @return the front element, or null if the deque is empty
     */
    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (lock) {
            return size == 0 ? null : (E) buffer[head];
        }
    }

    /**
     * Returns the back element without removing it.
     *
     * @return the back element, or null if the deque is empty
     */
    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) buffer[(head + size - 1) % buffer.length];
        }
    }

    /** Removes all elements and wakes any blocked producers. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = null;
            }
            head = 0;
            size = 0;
            lock.notifyAll();
        }
    }
}
