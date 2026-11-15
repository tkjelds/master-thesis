@ThreadSafeQueue.ThreadSafe
public class ThreadSafeQueue {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head = 0;
    private int tail = 0;
    private final Object lock = new Object();

    public ThreadSafeQueue(int capacity) {
        elements = new Object[capacity];
    }

    public boolean enqueue(Object element) throws InterruptedException {
        synchronized (lock) {
            while (isFull()) {
                lock.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            lock.notifyAll();
            return true;
        }
    }

    public Object dequeue() throws InterruptedException {
        synchronized (lock) {
            while (isEmpty()) {
                lock.wait();
            }
            Object value = elements[head];
            head = (head + 1) % elements.length;
            lock.notifyAll();
            return value;
        }
    }

    private boolean isFull() {
        return (tail + 1) % elements.length == head;
    }

    private boolean isEmpty() {
        return tail == head;
    }
}
