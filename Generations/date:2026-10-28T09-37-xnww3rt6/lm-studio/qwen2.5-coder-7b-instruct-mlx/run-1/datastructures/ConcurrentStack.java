@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private T[] elements;
    private int size;

    @SuppressWarnings("unchecked")
    public ConcurrentStack(int capacity) {
        elements = (T[]) new Object[capacity];
        size = 0;
    }

    public synchronized T pop() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        T result = elements[--size];
        notifyAll();
        return result;
    }

    public synchronized void push(T element) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        elements[size++] = element;
        notifyAll();
    }

    public synchronized T peek() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        return elements[size - 1];
    }

    public synchronized int size() {
        return size;
    }
}
