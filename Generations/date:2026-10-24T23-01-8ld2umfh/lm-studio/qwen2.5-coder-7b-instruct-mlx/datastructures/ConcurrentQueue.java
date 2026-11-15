@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 10;
    private T[] items;
    private int head, tail;

    @SuppressWarnings("unchecked")
    public ConcurrentQueue(int capacity) {
        this.items = (T[]) new Object[capacity];
        this.head = 0;
        this.tail = 0;
    }

    public ConcurrentQueue() {
        this(DEFAULT_CAPACITY);
    }

    public synchronized void enqueue(T item) throws InterruptedException {
        while ((tail + 1) % items.length == head) {
            wait();
        }
        items[tail] = item;
        tail = (tail + 1) % items.length;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (head == tail) {
            wait();
        }
        T item = items[head];
        head = (head + 1) % items.length;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return (tail - head + items.length) % items.length;
    }
}
