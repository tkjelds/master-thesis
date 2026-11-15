@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int size;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void push(T value) throws InterruptedException {
        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }
            elements[size++] = value;
            monitor.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public T pop() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            int top = --size;
            T value = (T) elements[top];
            elements[top] = null;
            monitor.notifyAll();
            return value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int capacity() {
        synchronized (monitor) {
            return elements.length;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == elements.length;
        }
    }
}
