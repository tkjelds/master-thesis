@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;
    private volatile int size;

    public ArrayQueue(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = 0;
        size = 0;
    }

    public boolean enqueue(Object element) {
        synchronized (this) {
            while (size == elements.length) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            notifyAll();
            return true;
        }
    }

    public Object dequeue() {
        synchronized (this) {
            while (size == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            Object element = elements[head];
            head = (head + 1) % elements.length;
            size--;
            notifyAll();
            return element;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
