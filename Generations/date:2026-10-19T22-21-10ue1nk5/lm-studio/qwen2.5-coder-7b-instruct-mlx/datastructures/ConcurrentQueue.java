@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;
    private int head;
    private int tail;

    public ConcurrentQueue(int capacity) {
        elements = new Object[capacity];
        size = 0;
        head = 0;
        tail = 0;
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

    public synchronized T dequeue() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T element = (T) elements[head];
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }
}
