import java.util.NoSuchElementException;

/**
 * A thread-safe, bounded, double-ended queue (deque).
 *
 * Concurrency policy:
 *  - All state is guarded by a single private monitor object ({@code lock}).
 *    Every access to {@code buffer}, {@code head}, and {@code count} happens
 *    inside a synchronized block on that monitor, which provides mutual
 *    exclusion, visibility, and safe publication of stored elements.
 *  - Operations that cannot complete immediately follow this policy:
 *      * Blocking operations (putFirst, putLast, takeFirst, takeLast) wait
 *        on the monitor inside a while loop until the condition holds.
 *        A blocked thread is woken by notifyAll whenever the state changes.
 *        If the thread is interrupted, InterruptedException is thrown and
 *        the structure is left unchanged (the condition is checked before
 *        any mutation).
 *      * Non-blocking operations (offerFirst, offerLast, pollFirst,
 *        pollLast, peekFirst, peekLast) never wait. offer returns false when
 *        full; poll and peek return null when empty.
 *  - Null elements are rejected with NullPointerException, because null is
 *    used as the "empty" sentinel by the non-blocking poll/peek operations.
 *  - No busy-waiting, sleeping, or timed delays are used.
 *
 * @param <E> the type of elements held in this deque
 */
@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] buffer;
    private int head;   // index of the first element in the circular buffer
    private int count;  // number of elements currently stored

    /**
     * Creates a deque with the given fixed capacity.
     *
     * @param capacity maximum number of elements; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.buffer = new Object[capacity];
        this.head = 0;
        this.count = 0;
    }

    /**
     * Inserts an element at the front, blocking while the deque is full.
     *
     * @param e element to insert; must not be null
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws NullPointerException if e is null
     */
    public void putFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (count == buffer.length) {
                lock.wait();
            }
            head = (head - 1 + buffer.length) % buffer.length;
            buffer[head] = e;
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Inserts an element at the back, blocking while the deque is full.
     *
     * @param e element to insert; must not be null
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws NullPointerException if e is null
     */
    public void putLast(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (count == buffer.length) {
                lock.wait();
            }
            int index = (head + count) % buffer.length;
            buffer[index] = e;
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the front element, blocking while the deque is empty.
     *
     * @return the removed front element
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            Object e = buffer[head];
            buffer[head] = null;
            head = (head + 1) % buffer.length;
            count--;
            lock.notifyAll();
            return cast(e);
        }
    }

    /**
     * Removes and returns the back element, blocking while the deque is empty.
     *
     * @return the removed back element
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            int index = (head + count - 1) % buffer.length;
            Object e = buffer[index];
            buffer[index] = null;
            count--;
            lock.notifyAll();
            return cast(e);
        }
    }

    /**
     * Attempts to insert an element at the front without blocking.
     *
     * @param e element to insert; must not be null
     * @return true if inserted, false if the deque was full
     * @throws NullPointerException if e is null
     */
    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (count == buffer.length) {
                return false;
            }
            head = (head - 1 + buffer.length) % buffer.length;
            buffer[head] = e;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Attempts to insert an element at the back without blocking.
     *
     * @param e element to insert; must not be null
     * @return true if inserted, false if the deque was full
     * @throws NullPointerException if e is null
     */
    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (count == buffer.length) {
                return false;
            }
            int index = (head + count) % buffer.length;
            buffer[index] = e;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the front element without blocking.
     *
     * @return the front element, or null if the deque was empty
     */
    public E pollFirst() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            Object e = buffer[head];
            buffer[head] = null;
            head = (head + 1) % buffer.length;
            count--;
            lock.notifyAll();
            return cast(e);
        }
    }

    /**
     * Removes and returns the back element without blocking.
     *
     * @return the back element, or null if the deque was empty
     */
    public E pollLast() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            int index = (head + count - 1) % buffer.length;
            Object e = buffer[index];
            buffer[index] = null;
            count--;
            lock.notifyAll();
            return cast(e);
        }
    }

    /**
     * Returns the front element without removing it.
     *
     * @return the front element, or null if the deque is empty
     */
    public E peekFirst() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return cast(buffer[head]);
        }
    }

    /**
     * Returns the back element without removing it.
     *
     * @return the back element, or null if the deque is empty
     */
    public E peekLast() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return cast(buffer[(head + count - 1) % buffer.length]);
        }
    }

    /**
     * Removes all elements from the deque and wakes any blocked threads
     * so they can re-evaluate their conditions.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = null;
            }
            head = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    /**
     * @return the number of elements currently in the deque
     */
    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    /**
     * @return true if the deque contains no elements
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    /**
     * @return true if the deque has reached its capacity
     */
    public boolean isFull() {
        synchronized (lock) {
            return count == buffer.length;
        }
    }

    /**
     * @return the fixed capacity of this deque
     */
    public int capacity() {
        return buffer.length;
    }

    /**
     * Returns a consistent snapshot of the contents, front to back.
     *
     * @return string representation of the current contents
     */
    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(buffer[(head + i) % buffer.length]);
            }
            sb.append(']');
            return sb.toString();
        }
    }

    private static void checkNotNull(Object e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }

    @SuppressWarnings("unchecked")
    private E cast(Object o) {
        return (E) o;
    }
}
