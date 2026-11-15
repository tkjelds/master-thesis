@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E> {
    @interface ThreadSafe {}


    /** Custom ordering contract (replaces java.util.Comparator). */
    public interface Ordering<T> {
        /** Returns negative if a < b, zero if equal, positive if a > b. */
        int compare(T a, T b);
    }

    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    // Single intrinsic monitor guarding all mutable state below.
    private final Object lock = new Object();

    // Immutable configuration (final fields are safely published).
    private final Ordering<? super E> ordering;
    private final int capacity; // Integer.MAX_VALUE denotes unbounded

    // Guarded by lock.
    private Object[] heap;
    private int size;

    /**
     * Creates an unbounded priority queue ordered by natural ordering
     * (elements must implement Comparable).
     *
     * Policy: put never blocks (grows as needed); take blocks while empty.
     */
    public ConcurrentPriorityQueue() {
        this(Integer.MAX_VALUE, naturalOrdering());
    }

    /**
     * Creates a priority queue with the given capacity and ordering.
     *
     * Policy:
     *  - put(e) blocks while the queue is full.
     *  - offer(e) returns false immediately if the queue is full.
     *  - take() blocks while the queue is empty.
     *  - poll() returns null immediately if the queue is empty.
     *
     * @param capacity maximum number of elements (must be >= 1)
     * @param ordering ordering used to determine priority; the head is the
     *                 least element according to this ordering
     */
    public ConcurrentPriorityQueue(int capacity, Ordering<? super E> ordering) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (ordering == null) {
            throw new NullPointerException("ordering must not be null");
        }
        this.capacity = capacity;
        this.ordering = ordering;
        this.heap = new Object[Math.min(capacity, DEFAULT_INITIAL_CAPACITY)];
        this.size = 0;
    }

    @SuppressWarnings("unchecked")
    private static <T> Ordering<T> naturalOrdering() {
        return (a, b) -> ((Comparable<Object>) a).compareTo(b);
    }

    /**
     * Inserts an element, blocking while the queue is full.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insertLocked(e);
            lock.notifyAll();
        }
    }

    /**
     * Inserts an element if space is available.
     *
     * @return true if inserted, false if the queue is full
     */
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insertLocked(e);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the least element, blocking while the queue is empty.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = extractMinLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Removes and returns the least element, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = extractMinLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Returns the least element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) heap[0];
        }
    }

    /**
     * Returns the current number of elements.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the queue contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the maximum number of elements (Integer.MAX_VALUE if unbounded).
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Removes all elements and wakes any threads blocked in put().
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                heap[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers. All must be called while holding 'lock'.
    // ---------------------------------------------------------------------

    private void insertLocked(E e) {
        if (size == heap.length) {
            growLocked();
        }
        heap[size] = e;
        siftUpLocked(size);
        size++;
    }

    @SuppressWarnings("unchecked")
    private E extractMinLocked() {
        E min = (E) heap[0];
        size--;
        if (size > 0) {
            heap[0] = heap[size];
            heap[size] = null;
            siftDownLocked(0);
        } else {
            heap[0] = null;
        }
        return min;
    }

    private void growLocked() {
        int newLength = Math.max(heap.length * 2, DEFAULT_INITIAL_CAPACITY);
        if (newLength > capacity || newLength < 0) {
            newLength = capacity;
        }
        Object[] bigger = new Object[newLength];
        System.arraycopy(heap, 0, bigger, 0, size);
        heap = bigger;
    }

    @SuppressWarnings("unchecked")
    private void siftUpLocked(int index) {
        Object element = heap[index];
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Object parentElem = heap[parent];
            if (ordering.compare((E) element, (E) parentElem) >= 0) {
                break;
            }
            heap[index] = parentElem;
            index = parent;
        }
        heap[index] = element;
    }

    @SuppressWarnings("unchecked")
    private void siftDownLocked(int index) {
        Object element = heap[index];
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            if (right < size
                    && ordering.compare((E) heap[right], (E) heap[child]) < 0) {
                child = right;
            }
            if (ordering.compare((E) element, (E) heap[child]) <= 0) {
                break;
            }
            heap[index] = heap[child];
            index = child;
        }
        heap[index] = element;
    }
}
