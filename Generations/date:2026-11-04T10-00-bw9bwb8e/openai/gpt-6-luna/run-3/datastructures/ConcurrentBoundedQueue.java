@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        elements = new Object[capacity];
    }

    /**
     * Inserts an element, waiting while the queue is full.
     * Null elements are not permitted.
     */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (lock) {
            while (count == elements.length) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    /**
     * Inserts an element if space is available; otherwise returns false.
     * Null elements are not permitted.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (lock) {
            if (count == elements.length) {
                return false;
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the front element, waiting while the queue is empty.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Removes and returns the front element, or returns null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the front element without removing it, or null if the queue is empty.
     */
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E element = (E) elements[head];
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return elements.length - count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == elements.length;
        }
    }

    public void clear() {
        synchronized (lock) {
            while (count > 0) {
                elements[head] = null;
                head = (head + 1) % elements.length;
                count--;
            }
            tail = head;
            lock.notifyAll();
        }
    }

    private void enqueue(E element) {
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        count++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        return element;
    }
}
