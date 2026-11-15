@ConcurrentPriorityQueue.ThreadSafe
public final class ConcurrentPriorityQueue<E extends Comparable<? super E>> {
    @interface ThreadSafe {}


    private static final int DEFAULT_INITIAL_CAPACITY = 16;

    // Single monitor guarding all mutable state (heap array, size).
    private final Object lock = new Object();

    // Maximum number of elements; Integer.MAX_VALUE means effectively unbounded.
    private final int maxCapacity;

    // Binary min-heap storage. Only accessed while holding 'lock'.
    private Object[] heap;
    private int size;

    /**
     * Creates an unbounded priority queue (elements ordered by natural ordering,
     * smallest element is returned first).
     */
    public ConcurrentPriorityQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a bounded priority queue.
     *
     * Blocking policy:
     *  - put(e) blocks while the queue is full.
     *  - take() blocks while the queue is empty.
     *  - offer(e) and poll() never block; they return false / null immediately.
     *
     * @param maxCapacity maximum number of elements, must be >= 1
     */
    public ConcurrentPriorityQueue(int maxCapacity) {
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity must be >= 1");
        }
        this.maxCapacity = maxCapacity;
        this.heap = new Object[Math.min(DEFAULT_INITIAL_CAPACITY, maxCapacity)];
        this.size = 0;
    }

    /**
     * Inserts an element, blocking while the queue is full.
     *
     * @throws NullPointerException if e is null
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == maxCapacity) {
                lock.wait();
            }
            insertLocked(e);
            lock.notifyAll();
        }
    }

    /**
     * Inserts an element if space is available, without blocking.
     *
     * @return true if the element was added, false if the queue was full
     * @throws NullPointerException if e is null
     */
    public boolean offer(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == maxCapacity) {
                return false;
            }
            insertLocked(e);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the smallest element, blocking while the queue is empty.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = removeMinLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Removes and returns the smallest element without blocking.
     *
     * @return the smallest element, or null if the queue is empty
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = removeMinLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Returns the smallest element without removing it, or null if empty.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return cast(heap[0]);
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
     * Returns true if the queue currently contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the maximum capacity (Integer.MAX_VALUE if unbounded).
     */
    public int capacity() {
        return maxCapacity;
    }

    /**
     * Removes all elements. Blocked producers are woken to re-check capacity.
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

    // ----------------------------------------------------------------------
    // Internal helpers. All of these must be called while holding 'lock'.
    // ----------------------------------------------------------------------

    private void insertLocked(E e) {
        if (size == heap.length) {
            growLocked();
        }
        siftUpLocked(size, e);
        size++;
    }

    private E removeMinLocked() {
        Object result = heap[0];
        size--;
        Object last = heap[size];
        heap[size] = null;
        if (size > 0) {
            siftDownLocked(0, last);
        }
        return cast(result);
    }

    private void growLocked() {
        long target = Math.max((long) heap.length * 2L, (long) heap.length + 1L);
        int newCapacity = (int) Math.min(target, (long) maxCapacity);
        Object[] newHeap = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            newHeap[i] = heap[i];
        }
        heap = newHeap;
    }

    private void siftUpLocked(int k, Object x) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object p = heap[parent];
            if (compare(x, p) >= 0) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = x;
    }

    private void siftDownLocked(int k, Object x) {
        int half = size >>> 1;
        while (k < half) {
            int child = (k << 1) + 1;
            Object c = heap[child];
            int right = child + 1;
            if (right < size && compare(c, heap[right]) > 0) {
                child = right;
                c = heap[child];
            }
            if (compare(x, c) <= 0) {
                break;
            }
            heap[k] = c;
            k = child;
        }
        heap[k] = x;
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
