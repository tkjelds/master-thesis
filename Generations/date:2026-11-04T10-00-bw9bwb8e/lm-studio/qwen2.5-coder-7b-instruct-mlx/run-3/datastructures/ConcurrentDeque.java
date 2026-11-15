@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<T> {
    @interface ThreadSafe {}

    private final T[] elements;
    private int head, tail;

    @SuppressWarnings("unchecked")
    public ConcurrentDeque(int capacity) {
        elements = (T[]) new Object[capacity];
        head = tail = 0;
    }

    public synchronized void push(T element) throws InterruptedException {
        while ((tail + 1) % elements.length == head) {
            wait();
        }
        elements[head] = element;
        head = (head + 1) % elements.length;
        notifyAll();
    }

    public synchronized T pop() throws InterruptedException {
        while (head == tail) {
            wait();
        }
        head = (head - 1 + elements.length) % elements.length;
        T element = elements[head];
        notifyAll();
        return element;
    }

    public synchronized void offer(T element) throws InterruptedException {
        while ((tail + 1) % elements.length == head) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        notifyAll();
    }

    public synchronized T poll() throws InterruptedException {
        while (head == tail) {
            wait();
        }
        head = (head + 1) % elements.length;
        T element = elements[head];
        notifyAll();
        return element;
    }
}
