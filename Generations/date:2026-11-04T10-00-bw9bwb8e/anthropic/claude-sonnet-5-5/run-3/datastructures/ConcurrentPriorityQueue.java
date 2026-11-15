import java.io.Serializable;

/**
 * A thread-safe, optionally bounded, blocking priority queue modeled on C++ std::priority_queue.
 *
 * Ordering: like std::priority_queue, the element that is "largest" according to the
 * ordering (natural ordering, or the supplied comparator) has the highest priority and is
 * returned first by take/poll/peek. Ties are broken arbitrarily.
 *
 * Blocking policy:
 *  - put(e) blocks while the queue is full (only possible for bounded queues).
 *  - take() blocks while the queue is empty.
 *  - offer(e)/poll() never block; they return false/null if they cannot complete immediately.
 *  - Timed offer/poll wait up to the given timeout using wait with timeouts.
 *
 * All state is guarded by a single intrinsic monitor. Waits are always in while loops.
 */
@ConcurrentPriorityQueue.ThreadSafe
public class ConcurrentPriorityQueue<E> {
    @interface ThreadSafe {}


    /** Own comparator interface, since java.util.Comparator may not be imported. */
    public interface PriorityComparator<T> {
        int compare(T a, T b);
    }

    private final Object lock = new Object();
    private final PriorityComparator<? super E> comparator;
    private final int capacity;

    // Guarded by lock
    private Object[] heap;
    private int count;

    public ConcurrentPriorityQueue() {
        this(Integer.MAX_VALUE, null);
    }

    public ConcurrentPriorityQueue(int capacity) {
        this(capacity, null);
    }

    public ConcurrentPriorityQueue(PriorityComparator<? super E> comparator) {
        this(Integer.MAX_VALUE, comparator);
    }

    public ConcurrentPriorityQueue(int capacity, PriorityComparator<? super E> comparator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.comparator = comparator;
        int initial = capacity < 16 ? capacity : 16;
        this.heap = new Object[initial];
        this.count = 0;
    }

    // ---------- insertion ----------

    /** Inserts the element, blocking while the queue is full. */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (count >= capacity) {
                lock.wait();
            }
            insertLocked(e);
            lock.notifyAll();
        }
    }

    /** Inserts the element if space is available right now. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (count >= capacity) {
                return false;
            }
            insertLocked(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Inserts the element, waiting up to timeoutMillis for space. */
    public boolean offer(E e, long timeoutMillis) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long remaining = timeoutMillis * 1000000L;
        synchronized (lock) {
            long deadline = System.nanoTime() + remaining;
            while (count >= capacity) {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            insertLocked(e);
            lock.notifyAll();
            return true;
        }
    }

    // ---------- removal ----------

    /** Removes and returns the highest-priority element, blocking while empty. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E result = removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Removes and returns the highest-priority element, or null if empty. */
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E result = removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Removes and returns the highest-priority element, waiting up to timeoutMillis; null on timeout. */
    public E poll(long timeoutMillis) throws InterruptedException {
        long remaining = timeoutMillis * 1000000L;
        synchronized (lock) {
            long deadline = System.nanoTime() + remaining;
            while (count == 0) {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            E result = removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Returns (without removing) the highest-priority element, or null if empty. */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return count == 0 ? null : (E) heap[0];
        }
    }

    // ---------- status ----------

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

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - count;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                heap[i] = null;
            }
            count = 0;
            lock.notifyAll();
        }
    }

    // ---------- internals (call only while holding lock) ----------

    private void insertLocked(E e) {
        if (count == heap.length) {
            growLocked();
        }
        heap[count] = e;
        siftUpLocked(count, e);
        count++;
    }

    private void growLocked() {
        long doubled = (long) heap.length * 2L;
        if (doubled < 2) doubled = 2;
        int newLen = doubled > capacity ? capacity : (int) doubled;
        if (newLen <= heap.length) {
            newLen = heap.length + 1;
        }
        Object[] n = new Object[newLen];
        for (int i = 0; i < count; i++) {
            n[i] = heap[i];
        }
        heap = n;
    }

    @SuppressWarnings("unchecked")
    private E removeTopLocked() {
        E top = (E) heap[0];
        count--;
        E last = (E) heap[count];
        heap[count] = null;
        if (count > 0) {
            siftDownLocked(0, last);
        }
        return top;
    }

    @SuppressWarnings("unchecked")
    private void siftUpLocked(int index, E item) {
        int k = index;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            E p = (E) heap[parent];
            if (compareLocked(item, p) <= 0) {
                break;
            }
            heap[k] = p;
            k = parent;
        }
        heap[k] = item;
    }

    @SuppressWarnings("unchecked")
    private void siftDownLocked(int index, E item) {
        int k = index;
        int half = count >>> 1;
        while (k < half) {
            int child = 2 * k + 1;
            E c = (E) heap[child];
            int right = child + 1;
            if (right < count && compareLocked((E) heap[right], c) > 0) {
                child = right;
                c = (E) heap[child];
            }
            if (compareLocked(item, c) >= 0) {
                break;
            }
            heap[k] = c;
            k = child;
        }
        heap[k] = item;
    }

    @SuppressWarnings("unchecked")
    private int compareLocked(E a, E b) {
        if (comparator != null) {
            return ((PriorityComparator<Object>) comparator).compare(a, b);
        }
        return ((Comparable<Object>) a).compareTo(b);
    }
}
