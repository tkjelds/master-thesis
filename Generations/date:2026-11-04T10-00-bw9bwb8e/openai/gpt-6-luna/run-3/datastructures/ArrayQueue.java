@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting while the queue is full.
     */
    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (this) {
            while (size == elements.length) {
                wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            notifyAll();
        }
    }

    /**
     * Removes and returns the front element, waiting while the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            notifyAll();
            return element;
        }
    }

    /**
     * Adds an element immediately, returning false if the queue is full.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (this) {
            if (size == elements.length) {
                return false;
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the front element immediately, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            notifyAll();
            return element;
        }
    }

    /**
     * Returns the front element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (this) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == elements.length;
        }
    }
}
