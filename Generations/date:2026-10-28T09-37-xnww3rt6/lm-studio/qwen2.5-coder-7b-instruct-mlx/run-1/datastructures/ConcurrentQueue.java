@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private T[] elements;
    private int head = 0;
    private int tail = 0;

    @SuppressWarnings("unchecked")
    public ConcurrentQueue(int capacity) {
        elements = (T[]) new Object[capacity];
    }

    public synchronized void enqueue(T item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T item = elements[head];
        head = (head + 1) % elements.length;
        notifyAll();
        return item;
    }

    private synchronized boolean isFull() {
        return (tail + 1) % elements.length == head;
    }

    private synchronized boolean isEmpty() {
        return head == tail;
    }
}
