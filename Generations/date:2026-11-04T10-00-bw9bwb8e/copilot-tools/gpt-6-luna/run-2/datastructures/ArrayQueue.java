@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting while the queue is full.
     */
    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the next element, waiting while the queue is empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Adds an element immediately, returning false if the queue is full.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the next element immediately, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }
}
