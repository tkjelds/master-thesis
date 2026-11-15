@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int size = 0;

    public ConcurrentStack(int capacity) {
        this.elements = new Object[capacity];
    }

    public synchronized void push(T item) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        elements[size++] = item;
        notifyAll();
    }

    @SuppressWarnings("unchecked")
    public synchronized T pop() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T item = (T) elements[--size];
        notifyAll();
        return item;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
