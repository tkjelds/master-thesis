@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private T[] elements;
    private int top;

    @SuppressWarnings("unchecked")
    public ConcurrentStack(int capacity) {
        elements = (T[]) new Object[capacity];
        top = -1;
    }

    public synchronized void push(T item) throws InterruptedException {
        while (top == elements.length - 1) {
            wait();
        }
        elements[++top] = item;
        notifyAll();
    }

    public synchronized T pop() throws InterruptedException {
        while (top == -1) {
            wait();
        }
        T item = elements[top--];
        notifyAll();
        return item;
    }

    public synchronized T peek() throws InterruptedException {
        while (top == -1) {
            wait();
        }
        return elements[top];
    }

    public synchronized boolean isEmpty() {
        return top == -1;
    }
}
