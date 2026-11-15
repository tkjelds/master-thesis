@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size = 0;
    private int head = 0;
    private int tail = 0;

    public ArrayQueue(int capacity) {
        elements = new Object[capacity];
    }

    public synchronized boolean enqueue(Object element) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
        notifyAll();
        return true;
    }

    public synchronized Object dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Object element = elements[head];
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }

    private boolean isFull() {
        return size == elements.length;
    }

    private boolean isEmpty() {
        return size == 0;
    }
}
