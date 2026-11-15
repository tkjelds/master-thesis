@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (this) {
            while (size == capacity) {
                wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            size--;
            notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public int capacity() {
        return capacity;
    }
}
