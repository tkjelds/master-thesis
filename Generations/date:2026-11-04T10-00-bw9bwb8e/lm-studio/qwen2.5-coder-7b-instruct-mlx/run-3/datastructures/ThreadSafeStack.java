@ThreadSafeStack.ThreadSafe
public class ThreadSafeStack<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int top;
    private static final int DEFAULT_CAPACITY = 10;

    public ThreadSafeStack() {
        this.elements = new Object[DEFAULT_CAPACITY];
        this.top = -1;
    }

    public synchronized void push(T item) throws InterruptedException {
        while (top == elements.length - 1) {
            wait();
        }
        elements[++top] = item;
        notifyAll();
    }

    @SuppressWarnings("unchecked")
    public synchronized T pop() throws InterruptedException {
        while (top == -1) {
            wait();
        }
        T item = (T) elements[top--];
        notifyAll();
        return item;
    }

    public synchronized T peek() throws InterruptedException {
        while (top == -1) {
            wait();
        }
        @SuppressWarnings("unchecked")
        T item = (T) elements[top];
        return item;
    }

    public synchronized boolean isEmpty() {
        return top == -1;
    }
}
