@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;

    private int head;
    private int tail;
    private int size;

    /**
     * Creates a bounded FIFO collection. Null elements are not permitted.
     */
    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Waits for space if the collection is full.
     */
    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    /**
     * Adds an element if space is immediately available.
     */
    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Waits for an element if the collection is empty.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Removes the oldest element, or returns null if the collection is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the oldest element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[head];
        }
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

    public int capacity() {
        return elements.length;
    }

    /**
     * Removes all elements and wakes threads waiting for space.
     */
    public void clear() {
        synchronized (lock) {
            if (size == 0) {
                return;
            }
            while (size > 0) {
                elements[head] = null;
                head = nextIndex(head);
                size--;
            }
            head = 0;
            tail = 0;
            lock.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Called only while holding lock.
    private void enqueue(E element) {
        elements[tail] = element;
        tail = nextIndex(tail);
        size++;
    }

    // Called only while holding lock, when the collection is nonempty.
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) elements[head];
        elements[head] = null;
        head = nextIndex(head);
        size--;
        return element;
    }

    private int nextIndex(int index) {
        index++;
        return index == elements.length ? 0 : index;
    }
}
