@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final int capacity;

    // Guarded by this.
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
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (this) {
            while (count == capacity) {
                wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            count++;
            notifyAll();
        }
    }

    /**
     * Removes and returns the next element, waiting while the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            count--;
            notifyAll();
            return element;
        }
    }

    /**
     * Adds an element immediately, returning false if the queue is full.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (this) {
            if (count == capacity) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            count++;
            notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the next element immediately, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (this) {
            if (count == 0) {
                return null;
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            count--;
            notifyAll();
            return element;
        }
    }

    /**
     * Returns the next element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (this) {
            return count == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return count == capacity;
        }
    }
}
