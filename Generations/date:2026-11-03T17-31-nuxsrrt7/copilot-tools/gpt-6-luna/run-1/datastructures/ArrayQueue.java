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

    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element must not be null");
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
