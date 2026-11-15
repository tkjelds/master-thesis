@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head = 0, tail = 0;

    public ConcurrentQueue(int capacity) {
        elements = new Object[capacity];
    }

    public synchronized boolean enqueue(T element) throws InterruptedException {
        while (isFull()) wait();
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        notifyAll();
        return true;
    }

    public synchronized T dequeue() throws InterruptedException {
        while (isEmpty()) wait();
        @SuppressWarnings("unchecked")
        T element = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        notifyAll();
        return element;
    }

    private synchronized boolean isFull() {
        return (tail + 1) % elements.length == head;
    }

    private synchronized boolean isEmpty() {
        return tail == head;
    }
}
