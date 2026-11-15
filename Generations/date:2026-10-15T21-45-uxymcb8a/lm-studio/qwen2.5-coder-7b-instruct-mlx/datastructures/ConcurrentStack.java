@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;
    private final Object lock = new Object();

    public ConcurrentStack(int capacity) {
        elements = new Object[capacity];
        size = 0;
    }

    public void push(T item) {
        synchronized (lock) {
            while (size == elements.length) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            elements[size++] = item;
            lock.notifyAll();
        }
    }

    public T pop() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            T item = (T) elements[--size];
            elements[size] = null; // Help GC
            lock.notifyAll();
            return item;
        }
    }

    public T peek() {
        synchronized (lock) {
            while (size == 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return (T) elements[size - 1];
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }
}
