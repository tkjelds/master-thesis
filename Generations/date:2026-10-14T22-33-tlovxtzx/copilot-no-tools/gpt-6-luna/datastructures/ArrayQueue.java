@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    /**
     * Inserts an element, waiting until space is available.
     */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the next element, waiting until one is available.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = removeHead();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Inserts an element immediately, returning false if the queue is full.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the next element immediately, or null if empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = removeHead();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the next element without removing it, or null if empty.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E element = (E) elements[head];
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    @SuppressWarnings("unchecked")
    private E removeHead() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return element;
    }
}
