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

    public synchronized void enqueue(Object element) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
        notifyAll();
    }

    public synchronized Object dequeue() throws InterruptedException {
        while (size == 0) {
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
}
