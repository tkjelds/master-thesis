@ConcurrentStack.ThreadSafe
public class ConcurrentStack {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;

    public ConcurrentStack(int capacity) {
        this.elements = new Object[capacity];
        this.size = 0;
    }

    public synchronized void push(Object element) {
        if (size == elements.length) {
            notifyAll();
            while (size == elements.length) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        elements[size++] = element;
    }

    public synchronized Object pop() {
        while (size == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object element = elements[--size];
        notifyAll();
        return element;
    }

    public synchronized int size() {
        return size;
    }
}
