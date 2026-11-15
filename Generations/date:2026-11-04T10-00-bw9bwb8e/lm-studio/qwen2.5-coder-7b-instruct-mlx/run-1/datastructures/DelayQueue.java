@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}

    private static final int CAPACITY = 100;
    private Object[] queue = new Object[CAPACITY];
    private int head = 0;
    private int tail = 0;

    public synchronized void put(T element, long timeout) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        queue[tail] = element;
        tail = (tail + 1) % CAPACITY;
        notifyAll();
        Thread.sleep(timeout);
    }

    public synchronized T take() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T element = (T) queue[head];
        head = (head + 1) % CAPACITY;
        notifyAll();
        return element;
    }

    private boolean isFull() {
        return (tail + 1) % CAPACITY == head;
    }

    private boolean isEmpty() {
        return head == tail;
    }
}
