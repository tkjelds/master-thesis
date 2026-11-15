@BoundedBlockingCollection.ThreadSafe
public final class BoundedBlockingCollection<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;
    private int tail;
    private int count;

    /**
     * Creates a bounded, thread-safe FIFO collection.
     *
     * Policy:
     *  - put(e) blocks while the collection is full.
     *  - take() blocks while the collection is empty.
     *  - offer(e) and poll() never block; they return false / null on full / empty.
     *  - null elements are rejected with NullPointerException.
     *  - Blocking operations respond to thread interruption by throwing
     *    InterruptedException; the collection state is left unchanged in that case.
     */
    public BoundedBlockingCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /** Blocks until space is available, then inserts the element. */
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

    /** Blocks until an element is available, then removes and returns it. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E element = extractHeadLocked();
            lock.notifyAll();
            return element;
        }
    }

    /** Non-blocking insert. Returns false if the collection is full. */
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

    /** Non-blocking removal. Returns null if the collection is empty. */
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = extractHeadLocked();
            lock.notifyAll();
            return element;
        }
    }

    /** Removes all elements and wakes any blocked producers. */
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

    public int capacity() {
        return items.length;
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

    /** Must be called while holding lock and with count > 0. */
    @SuppressWarnings("unchecked")
    private E extractHeadLocked() {
        Object value = items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return (E) value;
    }
}
