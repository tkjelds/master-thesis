@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int head = 0;
    private int tail = 0;

    public ConcurrentQueue(int capacity) {
        elements = new Object[capacity];
    }

    public synchronized void enqueue(T element) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        @SuppressWarnings("unchecked")
        T element = (T) elements[head];
        head = (head + 1) % elements.length;
        notifyAll();
        return element;
    }

    private boolean isFull() {
        return (tail + 1) % elements.length == head;
    }

    private boolean isEmpty() {
        return head == tail;
    }
}
