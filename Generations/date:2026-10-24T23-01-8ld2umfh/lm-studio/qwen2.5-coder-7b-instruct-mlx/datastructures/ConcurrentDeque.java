@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head, tail;

    public ConcurrentDeque(int capacity) {
        elements = new Object[capacity];
        head = tail = 0;
    }

    public synchronized void addFirst(T element) throws InterruptedException {
        while (isFull()) wait();
        elements[head = (head - 1 + elements.length) % elements.length] = element;
        notifyAll();
    }

    public synchronized T removeFirst() throws InterruptedException {
        while (isEmpty()) wait();
        T element = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        notifyAll();
        return element;
    }

    public synchronized void addLast(T element) throws InterruptedException {
        while (isFull()) wait();
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        notifyAll();
    }

    public synchronized T removeLast() throws InterruptedException {
        while (isEmpty()) wait();
        int index = (tail - 1 + elements.length) % elements.length;
        T element = (T) elements[index];
        elements[index] = null;
        tail = index;
        notifyAll();
        return element;
    }

    private synchronized boolean isFull() {
        return (tail + 1) % elements.length == head;
    }

    private synchronized boolean isEmpty() {
        return tail == head;
    }
}
