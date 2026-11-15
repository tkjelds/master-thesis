@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not allowed");
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

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not allowed");
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

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
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
}
