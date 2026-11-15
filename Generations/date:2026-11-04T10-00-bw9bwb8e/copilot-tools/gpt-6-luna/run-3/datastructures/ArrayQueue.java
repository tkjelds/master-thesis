@ArrayQueue.ThreadSafe
public final class ArrayQueue<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void enqueue(T element) throws InterruptedException {
        synchronized (this) {
            while (size == elements.length) {
                wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }

            T element = (T) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == elements.length;
        }
    }
}
