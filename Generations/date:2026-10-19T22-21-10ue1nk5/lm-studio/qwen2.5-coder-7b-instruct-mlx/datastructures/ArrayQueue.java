@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int putIndex, takeIndex, count;

    public ArrayQueue(int capacity) {
        elements = new Object[capacity];
    }

    public synchronized void put(Object element) throws InterruptedException {
        while (count == elements.length) {
            wait();
        }
        elements[putIndex] = element;
        if (++putIndex == elements.length) {
            putIndex = 0;
        }
        count++;
        notifyAll();
    }

    public synchronized Object take() throws InterruptedException {
        while (count == 0) {
            wait();
        }
        Object element = elements[takeIndex];
        if (++takeIndex == elements.length) {
            takeIndex = 0;
        }
        count--;
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return count;
    }
}
