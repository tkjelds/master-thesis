@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private final int capacity;
    private T[] elements;
    private int head, tail, count;

    public ConcurrentQueue(int capacity) {
        this.capacity = capacity;
        elements = (T[]) new Object[capacity];
    }

    public synchronized void enqueue(T item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = item;
        tail = (tail + 1) % capacity;
        count++;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T item = elements[head];
        head = (head + 1) % capacity;
        count--;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return count;
    }

    private boolean isFull() {
        return count == capacity;
    }

    private boolean isEmpty() {
        return count == 0;
    }
}
