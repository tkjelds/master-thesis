@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque {
    @interface ThreadSafe {}

    private Object[] elements;
    private int head, tail;

    public ConcurrentDeque(int capacity) {
        this.elements = new Object[capacity];
        this.head = 0;
        this.tail = -1;
    }

    public synchronized void addFirst(Object element) {
        if (isFull()) {
            throw new IllegalStateException("Deque is full");
        }
        head = (head - 1 + elements.length) % elements.length;
        elements[head] = element;
        notifyAll();
    }

    public synchronized Object removeFirst() {
        if (isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object element = elements[head];
        head = (head + 1) % elements.length;
        notifyAll();
        return element;
    }

    public synchronized void addLast(Object element) {
        if (isFull()) {
            throw new IllegalStateException("Deque is full");
        }
        tail = (tail + 1) % elements.length;
        elements[tail] = element;
        notifyAll();
    }

    public synchronized Object removeLast() {
        if (isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object element = elements[tail];
        tail = (tail - 1 + elements.length) % elements.length;
        notifyAll();
        return element;
    }

    public synchronized boolean isEmpty() {
        return head == (tail + 1) % elements.length;
    }

    public synchronized boolean isFull() {
        return (tail - head + 1) % elements.length == elements.length;
    }
}
