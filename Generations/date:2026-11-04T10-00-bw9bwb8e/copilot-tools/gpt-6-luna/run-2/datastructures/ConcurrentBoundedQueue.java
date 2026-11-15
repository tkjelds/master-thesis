@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}

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
            throw new NullPointerException("element must not be null");
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

    public E take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E element = removeHead();
            notifyAll();
            return element;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
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

    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E element = removeHead();
            notifyAll();
            return element;
        }
    }

    public E peek() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E element = (E) elements[head];
            return element;
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

    @SuppressWarnings("unchecked")
    private E removeHead() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        return element;
    }
}
