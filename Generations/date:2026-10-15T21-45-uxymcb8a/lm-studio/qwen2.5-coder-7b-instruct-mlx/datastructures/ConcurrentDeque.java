@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentDeque(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = 1;
        size = 0;
    }

    public synchronized void addFirst(T element) {
        if (isFull()) {
            throw new IllegalStateException("Deque is full");
        }
        elements[head] = element;
        head = (head - 1 + elements.length) % elements.length;
        size++;
        notifyAll();
    }

    public synchronized void addLast(T element) {
        if (isFull()) {
            throw new IllegalStateException("Deque is full");
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
        notifyAll();
    }

    public synchronized T removeFirst() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        T element = (T) elements[head];
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized T removeLast() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        tail = (tail - 1 + elements.length) % elements.length;
        T element = (T) elements[tail];
        size--;
        notifyAll();
        return element;
    }

    public synchronized T getFirst() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        return (T) elements[head];
    }

    public synchronized T getLast() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        return (T) elements[(tail - 1 + elements.length) % elements.length];
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized boolean isFull() {
        return size == elements.length;
    }
}
