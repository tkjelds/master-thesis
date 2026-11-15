@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;

    // All mutable queue state is guarded by lock.
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting while the queue is full.
     */
    public void enqueue(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            elements[tail] = element;
            tail = increment(tail);
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the head element, waiting while the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = increment(head);
            count--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Adds an element immediately, returning false if the queue is full.
     */
    public boolean offer(E element) {
        requireNonNull(element);
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            elements[tail] = element;
            tail = increment(tail);
            count++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the head element immediately, or returns null if empty.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = increment(head);
            count--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the head element, or null if the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return count == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public int capacity() {
        return capacity;
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

    /**
     * Removes all queued elements and wakes threads waiting to enqueue.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                elements[(head + i) % capacity] = null;
            }
            head = 0;
            tail = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    private int increment(int index) {
        return index + 1 == capacity ? 0 : index + 1;
    }

    private void requireNonNull(E element) {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }
    }
}
