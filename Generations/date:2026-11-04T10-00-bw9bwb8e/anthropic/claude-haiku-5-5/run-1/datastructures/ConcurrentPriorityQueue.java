@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E> {
    @interface ThreadSafe {}


    /**
     * Ordering abstraction (own functional interface, so no java.util imports are needed).
     * Returns a negative value if a < b, zero if equal, positive if a > b.
     */
    public interface Order<T> {
        int compare(T a, T b);
    }

    /*
     * Concurrency policy:
     *  - The queue is bounded by a fixed capacity.
     *  - put(e)      : blocks while the queue is full, until space is available.
     *  - offer(e)    : never blocks; returns false if the queue is full.
     *  - take()      : blocks while the queue is empty, until an element is available.
     *  - poll()      : never blocks; returns null if the queue is empty.
     *  - poll(ms)    : blocks at most ms milliseconds; returns null on timeout.
     *  - peek()      : never blocks; returns null if empty.
     *  - null elements are rejected with NullPointerException.
     *  - Blocking methods throw InterruptedException if the calling thread is interrupted;
     *    the queue state is unchanged in that case.
     *
     * All state is guarded by the monitor 'lock'. Every state change is followed by
     * notifyAll() so that waiting producers and consumers re-evaluate their conditions.
     * All waits are guarded by while loops.
     */

    private final Object lock = new Object();
    private final Object[] heap;
    private final int capacity;
    private final Order<E> order;
    private int size; // guarded by lock

    public ConcurrentPriorityQueue(int capacity) {
        this(capacity, naturalOrder());
    }

    public ConcurrentPriorityQueue(int capacity, Order<E> order) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (order == null) {
            throw new NullPointerException("order must not be null");
        }
        this.capacity = capacity;
        this.order = order;
        this.heap = new Object[capacity];
        this.size = 0;
    }

    @SuppressWarnings("unchecked")
    private static <T> Order<T> naturalOrder() {
        return (a, b) -> ((Comparable<? super T>) a).compareTo(b);
    }

    public int capacity() {
        return capacity; // immutable after construction
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Blocks while the queue is full. */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insertLocked(e);
            lock.notifyAll();
        }
    }

    /** Non-blocking insert. Returns false if the queue is full. */
    public boolean offer(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insertLocked(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Blocks while the queue is empty, then removes and returns the smallest element. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeMinLocked();
        }
    }

    /** Non-blocking removal. Returns null if the queue is empty. */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeMinLocked();
        }
    }

    /**
     * Waits at most timeoutMillis for an element. Returns null on timeout.
     * Uses deadline-based waiting with guarded while loop (no sleeping).
     */
    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (lock) {
            while (size == 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining);
            }
            return removeMinLocked();
        }
    }

    /** Returns the smallest element without removing it, or null if empty. */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return castElement(heap[0]);
        }
    }

    /** Removes all elements and wakes any blocked producers. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ----- Private helpers: all of these must be called while holding 'lock' -----

    private void insertLocked(E e) {
        heap[size] = e;
        siftUpLocked(size);
        size++;
    }

    private E removeMinLocked() {
        E result = castElement(heap[0]);
        size--;
        Object last = heap[size];
        heap[size] = null;
        if (size > 0) {
            heap[0] = last;
            siftDownLocked(0);
        }
        lock.notifyAll();
        return result;
    }

    private void siftUpLocked(int k) {
        Object x = heap[k];
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object p = heap[parent];
            if (compare(castElement(x), castElement(p)) >= 0) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = x;
    }

    private void siftDownLocked(int k) {
        Object x = heap[k];
        int half = size >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            int right = child + 1;
            if (right < size && compare(castElement(heap[right]), castElement(heap[child])) < 0) {
                child = right;
            }
            if (compare(castElement(x), castElement(heap[child])) <= 0) {
                break;
            }
            heap[k] = heap[child];
            k = child;
        }
        heap[k] = x;
    }

    private int compare(E a, E b) {
        return order.compare(a, b);
    }

    @SuppressWarnings("unchecked")
    private E castElement(Object o) {
        return (E) o;
    }

    private static void checkNotNull(Object e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
