@ArrayQueue.ThreadSafe
public final class ArrayQueue<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;
    private int tail;
    private int count;

    /**
     * Creates a bounded queue with the given fixed capacity.
     *
     * Blocking policy:
     *  - put(item) blocks while the queue is full.
     *  - take() blocks while the queue is empty.
     *  - offer(item) and poll() never block; they return false / null immediately.
     *  - Blocked threads respond to interruption by throwing InterruptedException
     *    and leaving the queue unchanged.
     *
     * Null elements are rejected with NullPointerException, since null is used
     * as the "no element" signal by poll().
     */
    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Inserts the element, waiting while the queue is full.
     */
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            enqueueLocked(item);
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the head element, waiting while the queue is empty.
     */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Non-blocking insert. Returns true if the element was added, false if full.
     */
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            enqueueLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Non-blocking remove. Returns the head element, or null if empty.
     */
    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Removes all elements and wakes any blocked producers.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < items.length; i++) {
                items[i] = null;
            }
            head = 0;
            tail = 0;
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
            return count == items.length;
        }
    }

    /**
     * The capacity is fixed at construction and the field is final,
     * so no synchronization is required to read it.
     */
    public int capacity() {
        return items.length;
    }

    // Must be called while holding lock and only when count < items.length.
    private void enqueueLocked(T item) {
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
    }

    // Must be called while holding lock and only when count > 0.
    @SuppressWarnings("unchecked")
    private T dequeueLocked() {
        Object value = items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return (T) value;
    }
}
