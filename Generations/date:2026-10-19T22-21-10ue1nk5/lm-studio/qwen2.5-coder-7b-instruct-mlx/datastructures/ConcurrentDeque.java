@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;

    public ConcurrentDeque(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = -1;
    }

    public synchronized void addFirst(T element) {
        ensureCapacity();
        elements[head = (head - 1 + elements.length) % elements.length] = element;
    }

    public synchronized T removeFirst() {
        if (head == tail) {
            throw new IllegalStateException("Deque is empty");
        }
        T element = (T) elements[head];
        head = (head + 1) % elements.length;
        return element;
    }

    public synchronized void addLast(T element) {
        ensureCapacity();
        tail = (tail + 1) % elements.length;
        elements[tail] = element;
    }

    public synchronized T removeLast() {
        if (head == tail) {
            throw new IllegalStateException("Deque is empty");
        }
        T element = (T) elements[tail];
        tail = (tail - 1 + elements.length) % elements.length;
        return element;
    }

    private synchronized void ensureCapacity() {
        if ((tail + 1) % elements.length == head) {
            int newCapacity = elements.length * 2;
            Object[] newElements = new Object[newCapacity];
            int size = (tail - head + elements.length) % elements.length;
            System.arraycopy(elements, 0, newElements, 0, size);
            head = 0;
            tail = size - 1;
            elements = newElements;
        }
    }

    public synchronized int size() {
        return (tail - head + elements.length) % elements.length;
    }

    public synchronized boolean isEmpty() {
        return head == tail;
    }
}
