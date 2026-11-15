@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private Object[] elements;
    private int head, tail, size;

    public ConcurrentQueue() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentQueue(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = 0;
        size = 0;
    }

    public synchronized void enqueue(T element) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
        notifyAll();
    }

    @SuppressWarnings("unchecked")
    public synchronized T dequeue() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T element = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }
}
