import java.io.Serializable;

/**
 * A thread-safe, bounded, FIFO concurrent collection (blocking queue style).
 *
 * Policy for operations that cannot complete immediately:
 *  - put(e): blocks while the collection is full.
 *  - take(): blocks while the collection is empty.
 *  - offer(e)/poll(): non-blocking; return false/null immediately on full/empty.
 *  - offer(e, timeoutMillis)/poll(timeoutMillis): block up to the timeout.
 *
 * All state is guarded by a single private monitor. Waits are always guarded by
 * while loops, and state changes wake waiters with notifyAll.
 *
 * Invariants: 0 <= count <= capacity; elements occupy buffer slots
 * head, head+1, ... (mod capacity); unused slots are null.
 */
@ConcurrentBoundedCollection.ThreadSafe
public class ConcurrentBoundedCollection<E> implements Serializable {
    @interface ThreadSafe {}

    private static final long serialVersionUID = 1L;

    private final Object lock = new Object();
    private final Object[] buffer;
    private final int capacity;
    private int head;   // guarded by lock
    private int count;  // guarded by lock

    public ConcurrentBoundedCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    public int capacity() {
        return capacity;
    }

    // ---- insertion ----

    /** Inserts element, waiting while the collection is full. */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            enqueue(e);
            lock.notifyAll();
        }
    }

    /** Inserts element if space is available; returns false if full. */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (count == capacity) return false;
            enqueue(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Inserts element, waiting up to timeoutMillis for space. */
    public boolean offer(E e, long timeoutMillis) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        long remaining = timeoutMillis * 1000000L;
        synchronized (lock) {
            long deadline = System.nanoTime() + remaining;
            while (count == capacity) {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            enqueue(e);
            lock.notifyAll();
            return true;
        }
    }

    // ---- removal ----

    /** Removes and returns the oldest element, waiting while empty. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = dequeue();
            lock.notifyAll();
            return e;
        }
    }

    /** Removes and returns the oldest element, or null if empty. */
    public E poll() {
        synchronized (lock) {
            if (count == 0) return null;
            E e = dequeue();
            lock.notifyAll();
            return e;
        }
    }

    /** Removes and returns the oldest element, waiting up to timeoutMillis; null on timeout. */
    public E poll(long timeoutMillis) throws InterruptedException {
        long remaining = timeoutMillis * 1000000L;
        synchronized (lock) {
            long deadline = System.nanoTime() + remaining;
            while (count == 0) {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                lock.wait(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            E e = dequeue();
            lock.notifyAll();
            return e;
        }
    }

    /** Returns the oldest element without removing it, or null if empty. */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return count == 0 ? null : (E) buffer[head];
        }
    }

    /** Removes one occurrence of the given element if present. */
    public boolean remove(Object o) {
        if (o == null) return false;
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                int idx = (head + i) % capacity;
                if (o.equals(buffer[idx])) {
                    // shift subsequent elements back by one
                    for (int j = i; j < count - 1; j++) {
                        int from = (head + j + 1) % capacity;
                        int to = (head + j) % capacity;
                        buffer[to] = buffer[from];
                    }
                    buffer[(head + count - 1) % capacity] = null;
                    count--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object o) {
        if (o == null) return false;
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (o.equals(buffer[(head + i) % capacity])) return true;
            }
            return false;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
            head = 0;
            count = 0;
            lock.notifyAll();
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

    public boolean isFull() {
        synchronized (lock) {
            return count == capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - count;
        }
    }

    /** Returns a consistent snapshot of the elements in FIFO order. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] out = new Object[count];
            for (int i = 0; i < count; i++) {
                out[i] = buffer[(head + i) % capacity];
            }
            return out;
        }
    }

    // ---- internal helpers; must be called while holding lock ----

    private void enqueue(E e) {
        buffer[(head + count) % capacity] = e;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E e = (E) buffer[head];
        buffer[head] = null;
        head = (head + 1) % capacity;
        count--;
        return e;
    }
}
