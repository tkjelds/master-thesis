@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
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

    public boolean offer(E element) {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
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

    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
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
