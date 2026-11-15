@ConcurrentDelayQueue.ThreadSafe
public final class ConcurrentDelayQueue<E extends ConcurrentDelayQueue.Delayed> {
    @interface ThreadSafe {}


    /**
     * Elements stored in the queue must expose an absolute deadline expressed in
     * the time base of System.nanoTime(). An element is considered expired (ready
     * to be taken) once System.nanoTime() - getDeadlineNanos() >= 0. The deadline
     * of an element must not change while the element is in the queue.
     */
    public interface Delayed {
        long getDeadlineNanos();
    }

    private static final int INITIAL_CAPACITY = 16;

    // All fields below are guarded by 'lock'.
    private final Object lock = new Object();
    private Object[] heap = new Object[INITIAL_CAPACITY];
    private int size = 0;

    public ConcurrentDelayQueue() {
    }

    /**
     * Inserts an element. This operation never blocks because the queue is unbounded.
     * Waiting consumers are woken so they can re-evaluate the head of the queue.
     *
     * @return always true
     * @throws NullPointerException if element is null
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == heap.length) {
                grow();
            }
            heap[size] = element;
            siftUp(size);
            size++;
            // notifyAll: a new element may become the head with an earlier deadline
            // than the one a waiting consumer is currently timing out on.
            lock.notifyAll();
        }
        return true;
    }

    /**
     * Retrieves and removes the head element, blocking until the queue is non-empty
     * and the head element has expired.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (size == 0) {
                    lock.wait();
                    continue;
                }
                long remaining = headOf().getDeadlineNanos() - System.nanoTime();
                if (remaining <= 0) {
                    return removeHead();
                }
                // Wait until the head is due (or until a notification changes the state).
                // Always wait at least 1 ms so wait(0) (infinite wait) is never requested.
                long millis = Math.max(1L, remaining / 1_000_000L);
                lock.wait(millis);
            }
        }
    }

    /**
     * Non-blocking retrieval. Removes and returns the head element only if it has
     * already expired; otherwise returns null.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            long remaining = headOf().getDeadlineNanos() - System.nanoTime();
            if (remaining > 0) {
                return null;
            }
            return removeHead();
        }
    }

    /**
     * Timed retrieval. Waits up to timeoutNanos for an expired head element.
     * Returns null if the timeout elapses before one becomes available.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public E poll(long timeoutNanos) throws InterruptedException {
        long timeoutDeadline = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            while (true) {
                long now = System.nanoTime();
                long untilTimeout = timeoutDeadline - now;
                if (size > 0) {
                    long remaining = headOf().getDeadlineNanos() - now;
                    if (remaining <= 0) {
                        return removeHead();
                    }
                    if (untilTimeout <= 0) {
                        return null;
                    }
                    long waitNanos = Math.min(remaining, untilTimeout);
                    lock.wait(Math.max(1L, waitNanos / 1_000_000L));
                } else {
                    if (untilTimeout <= 0) {
                        return null;
                    }
                    lock.wait(Math.max(1L, untilTimeout / 1_000_000L));
                }
            }
        }
    }

    /** Returns the number of elements currently held, expired or not. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the queue currently holds no elements. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Removes all elements from the queue. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
        }
    }

    // ----- Private helpers: must only be called while holding 'lock'. -----

    @SuppressWarnings("unchecked")
    private E headOf() {
        return (E) heap[0];
    }

    @SuppressWarnings("unchecked")
    private E removeHead() {
        E result = (E) heap[0];
        size--;
        heap[0] = heap[size];
        heap[size] = null;
        if (size > 0) {
            siftDown(0);
        }
        return result;
    }

    private static boolean earlier(Object a, Object b) {
        // Wraparound-safe comparison of deadlines.
        return ((Delayed) a).getDeadlineNanos() - ((Delayed) b).getDeadlineNanos() < 0;
    }

    private void siftUp(int index) {
        Object item = heap[index];
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!earlier(item, heap[parent])) {
                break;
            }
            heap[index] = heap[parent];
            index = parent;
        }
        heap[index] = item;
    }

    private void siftDown(int index) {
        Object item = heap[index];
        int half = size >>> 1;
        while (index < half) {
            int child = 2 * index + 1;
            int right = child + 1;
            if (right < size && earlier(heap[right], heap[child])) {
                child = right;
            }
            if (!earlier(heap[child], item)) {
                break;
            }
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = item;
    }

    private void grow() {
        Object[] bigger = new Object[heap.length * 2];
        System.arraycopy(heap, 0, bigger, 0, size);
        heap = bigger;
    }
}
