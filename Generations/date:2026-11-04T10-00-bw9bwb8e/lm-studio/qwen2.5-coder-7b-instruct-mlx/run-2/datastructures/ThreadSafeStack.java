@ThreadSafeStack.ThreadSafe
public class ThreadSafeStack {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;

    public ThreadSafeStack(int capacity) {
        elements = new Object[capacity];
        size = 0;
    }

    public synchronized void push(Object item) throws InterruptedException {
        while (size == elements.length) {
            wait();
        }
        elements[size++] = item;
        notifyAll();
    }

    public synchronized Object pop() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        Object item = elements[--size];
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return size;
    }
}
