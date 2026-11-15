/**
 * A thread-safe, bounded, blocking double-ended queue (deque).
 *
 * Concurrency policy:
 * - All state (backing array, head index, size) is guarded by a single private monitor.
 *   Every read and write of that state happens inside a synchronized block on that monitor,
 *   which provides mutual exclusion, visibility, and safe publication of stored elements.
 * - Blocking operations (putFirst, putLast, takeFirst, takeLast) wait while the deque is
 *   full (puts) or empty (takes). Waits are guarded by while loops to handle spurious
 *   wakeups. Every successful state change calls notifyAll so that waiters of either kind
 *   re-check their condition.
 * - Non-blocking operations (offerFirst, offerLast, pollFirst, pollLast) never wait. They
 *   return false or null immediately when the deque is full or empty, respectively.
 * - Interruption: blocking operations throw InterruptedException if the waiting thread is
 *   interrupted; the element is not inserted or removed in that case.
 * - Null elements are rejected with NullPointerException, since null is used as the
 *   "empty" marker by the non-blocking poll/peek operations.
 */
@ConcurrentBoundedDeque.ThreadSafe
public final class ConcurrentBoundedDeque<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;  // index of the first element in the circular buffer
    private int size;  // number of elements currently stored

    /**
     * Creates an empty deque with the given fixed capacity.
     *
     * @param capacity maximum number of elements; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public ConcurrentBoundedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.size = 0;
    }

    /**
     * Inserts the element at the front, blocking while the deque is full.
     *
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if e is null
     */
    public void putFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == items.length) {
                lock.wait();
            }
            head = (head == 0) ? items.length - 1 : head - 1;
            items[head] = e;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Inserts the element at the back, blocking while the deque is full.
     *
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if e is null
     */
    public void putLast(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == items.length) {
                lock.wait();
            }
            items[indexOfLast()] = null; // ensure slot is clean before reuse
            items[tailSlot()] = e;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the front element, blocking while the deque is empty.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    /**
     * Removes and returns the back element, blocking while the deque is empty.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            int idx = indexOfLast();
            E e = (E) items[idx];
            items[idx] = null;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    /**
     * Attempts to insert at the front without blocking.
     *
     * @return true if inserted, false if the deque was full
     * @throws NullPointerException if e is null
     */
    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == items.length) {
                return false;
            }
            head = (head == 0) ? items.length - 1 : head - 1;
            items[head] = e;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Attempts to insert at the back without blocking.
     *
     * @return true if inserted, false if the deque was full
     * @throws NullPointerException if e is null
     */
    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == items.length) {
                return false;
            }
            items[tailSlot()] = e;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the front element without blocking.
     *
     * @return the front element, or null if the deque is empty
     */
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            size--;
            lock.notifyAll();
            return e;
        }
    }

    /**
     * Removes and returns the back element without blocking.
     *
     * @return the back element, or null if the deque is empty
     */
    @SuppressWarnings("unchecked")
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int idx = indexOfLast();
            E e = (E) items[idx];
            items[idx] = null;
            size--;
            lock.notifyAll();
            return e;
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
            return size == 0 ? null : (E) items[head];
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
            return size == 0 ? null : (E) items[indexOfLast()];
        }
    }

    /**
     * Returns the number of elements currently in the deque.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the deque currently contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the fixed capacity of this deque.
     */
    public int capacity() {
        return items.length;
    }

    /**
     * Removes all elements and wakes any blocked threads so they re-check their conditions.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < items.length; i++) {
                items[i] = null;
            }
            head = 0;
            size = 0;
            lock.notifyAll();
        }
    }

    // ---- Helpers. All must be called while holding lock. ----

    /** Index of the slot immediately after the last element (where putLast writes). */
    private int tailSlot() {
        return (head + size) % items.length;
    }

    /** Index of the last element. Requires size > 0. */
    private int indexOfLast() {
        return (head + size - 1) % items.length;
    }

    private static void checkNotNull(Object e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
