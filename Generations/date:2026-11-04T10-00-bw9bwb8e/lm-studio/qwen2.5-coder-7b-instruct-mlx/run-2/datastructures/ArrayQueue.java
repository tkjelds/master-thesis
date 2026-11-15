@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private final int capacity;
    private final Object[] elements;
    private int head = 0;
    private int tail = 0;

    public ArrayQueue(int capacity) {
        this.capacity = capacity;
        elements = new Object[capacity];
    }

    public synchronized void enqueue(Object element) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % capacity;
        notifyAll();
    }

    public synchronized Object dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Object element = elements[head];
        head = (head + 1) % capacity;
        notifyAll();
        return element;
    }

    private boolean isFull() {
        return (tail + 1) % capacity == head;
    }

    private boolean isEmpty() {
        return head == tail;
    }
}
