import java.lang.reflect.Array;

/**
 * Thread-safe bounded FIFO queue.
 *
 * Blocking policy:
 *   - put(e): blocks while the queue is full, until space becomes available
 *     or the calling thread is interrupted (InterruptedException is thrown).
 *   - take(): blocks while the queue is empty, until an element is available
 *     or the calling thread is interrupted (InterruptedException is thrown).
 *
 * Non-blocking policy:
 *   - offer(e): returns false immediately if the queue is full.
 *   - poll(): returns null immediately if the queue is empty.
 *
 * Null elements are rejected with NullPointerException, since null is used
 * as the "no element" signal by poll().
 *
 * All state is guarded by a single private monitor. Every access to the
 * buffer, head, tail and count happens inside a synchronized block on that
 * monitor, which gives mutual exclusion, and the monitor's release/acquire
 * semantics give visibility and safe publication of enqueued elements.
 * All waits are guarded by while loops and are paired with notifyAll after
 * every state change.
 */
@ThreadSafeQueue.ThreadSafe
public final class ThreadSafeQueue<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;   // index of next element to remove
    private int tail;   // index where next element will be inserted
    private int count;  // number of elements currently stored

    public ThreadSafeQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.items = new Object[capacity];
    }

    /**
     * Inserts the element, blocking while the queue is full.
     */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            items[tail] = element;
            tail = (tail + 1) % items.length;
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the head element, blocking while the queue is empty.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            Object element = items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return cast(element);
        }
    }

    /**
     * Inserts the element if space is available; never blocks.
     *
     * @return true if the element was inserted, false if the queue was full
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            items[tail] = element;
            tail = (tail + 1) % items.length;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the head element if present; never blocks.
     *
     * @return the head element, or null if the queue was empty
     */
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            Object element = items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return cast(element);
        }
    }

    /**
     * Returns the current number of elements.
     */
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

    public boolean isFull() {
        synchronized (lock) {
            return count == items.length;
        }
    }

    /**
     * Returns the fixed maximum capacity. Immutable after construction.
     */
    public int capacity() {
        return items.length;
    }

    @SuppressWarnings("unchecked")
    private E cast(Object element) {
        return (E) element;
    }
}
