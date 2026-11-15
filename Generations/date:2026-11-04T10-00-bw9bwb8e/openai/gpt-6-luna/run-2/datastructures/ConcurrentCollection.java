@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Adds an element, waiting until space is available.
     */
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
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
     * Adds an element immediately, returning false if the collection is full.
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
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
     * Removes and returns the next element, waiting until one is available.
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E element = (E) dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Removes and returns the next element immediately, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = (E) dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the next element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (E) elements[head];
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

    private Object dequeue() {
        Object element = elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        return element;
    }
}
