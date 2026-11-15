@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    // Blocks while the bounded queue is full.
    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (monitor) {
            while (count == elements.length) {
                monitor.wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            count++;
            monitor.notifyAll();
        }
    }

    // Blocks while the queue is empty.
    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }

            Object element = elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            count--;
            monitor.notifyAll();
            return (E) element;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
        }
    }

    public int capacity() {
        return elements.length;
    }
}
