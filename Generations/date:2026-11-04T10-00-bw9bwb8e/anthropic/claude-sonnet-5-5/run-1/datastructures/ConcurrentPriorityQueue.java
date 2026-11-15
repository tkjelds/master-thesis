import java.lang.*; // java.lang is implicit; no other imports are used.

/**
 * A thread-safe, optionally bounded, blocking priority queue.
 *
 * Ordering policy: like C++ std::priority_queue, the "top" element is the
 * greatest one according to compareTo (max-heap).
 *
 * Blocking policy:
 *  - push(): blocks while the queue is full (only for bounded queues).
 *  - pop(): blocks while the queue is empty.
 *  - tryPush()/tryPop()/top() never block.
 *
 * All state is guarded by a single private monitor. Waits are always guarded
 * by while loops, and state changes call notifyAll.
 */
@ConcurrentPriorityQueue.ThreadSafe
public class ConcurrentPriorityQueue<T extends Comparable<? super T>> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final int capacity;      // maximum number of elements
    private Object[] heap;           // guarded by lock
    private int count;               // guarded by lock

    /** Creates an unbounded queue. */
    public ConcurrentPriorityQueue() {
        this(Integer.MAX_VALUE - 8);
    }

    /** Creates a bounded queue with the given maximum capacity. */
    public ConcurrentPriorityQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        int initial = capacity < 16 ? capacity : 16;
        this.heap = new Object[initial];
        this.count = 0;
    }

    /** Inserts an element, waiting while the queue is full. */
    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            while (count >= capacity) {
                lock.wait();
            }
            insertLocked(item);
            lock.notifyAll();
        }
    }

    /** Inserts an element if space is available; returns false if full. */
    public boolean tryPush(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (count >= capacity) {
                return false;
            }
            insertLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the greatest element, waiting while empty. */
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T result = removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Removes and returns the greatest element, or null if empty. */
    public T tryPop() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T result = removeTopLocked();
            lock.notifyAll();
            return result;
        }
    }

    /** Returns the greatest element without removing it, or null if empty. */
    @SuppressWarnings("unchecked")
    public T top() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (T) heap[0];
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

    public int capacity() {
        return capacity;
    }

    // ---- internal helpers; must be called while holding lock ----

    private void insertLocked(T item) {
        if (count == heap.length) {
            grow();
        }
        heap[count] = item;
        siftUp(count);
        count++;
    }

    @SuppressWarnings("unchecked")
    private T removeTopLocked() {
        T result = (T) heap[0];
        count--;
        Object last = heap[count];
        heap[count] = null;
        if (count > 0) {
            heap[0] = last;
            siftDown(0);
        }
        return result;
    }

    private void grow() {
        long doubled = (long) heap.length * 2L;
        int newLen = (doubled > capacity) ? capacity : (int) doubled;
        if (newLen <= heap.length) {
            newLen = capacity;
        }
        Object[] bigger = new Object[newLen];
        for (int i = 0; i < count; i++) {
            bigger[i] = heap[i];
        }
        heap = bigger;
    }

    @SuppressWarnings("unchecked")
    private int cmp(int i, int j) {
        return ((T) heap[i]).compareTo((T) heap[j]);
    }

    private void swap(int i, int j) {
        Object t = heap[i];
        heap[i] = heap[j];
        heap[j] = t;
    }

    private void siftUp(int idx) {
        while (idx > 0) {
            int parent = (idx - 1) >>> 1;
            if (cmp(idx, parent) > 0) {
                swap(idx, parent);
                idx = parent;
            } else {
                break;
            }
        }
    }

    private void siftDown(int idx) {
        while (true) {
            int left = 2 * idx + 1;
            if (left >= count || left < 0) {
                break;
            }
            int right = left + 1;
            int largest = left;
            if (right < count && cmp(right, left) > 0) {
                largest = right;
            }
            if (cmp(largest, idx) > 0) {
                swap(largest, idx);
                idx = largest;
            } else {
                break;
            }
        }
    }
}
