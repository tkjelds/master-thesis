@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private T[] elements;
    private int head;
    private int tail;
    private int size;

    @SuppressWarnings("unchecked")
    public ConcurrentQueue() {
        elements = (T[]) new Object[DEFAULT_CAPACITY];
        head = 0;
        tail = 0;
        size = 0;
    }

    public synchronized void enqueue(T item) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        size++;
        notifyAll();
    }

    @SuppressWarnings("unchecked")
    public synchronized T dequeue() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T item = elements[head];
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return size;
    }
}
