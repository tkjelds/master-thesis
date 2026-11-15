@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private E[] elements;
    private int head;
    private int tail;
    private volatile int size;

    @SuppressWarnings("unchecked")
    public ConcurrentDeque() {
        elements = (E[]) new Object[DEFAULT_CAPACITY];
        head = tail = 0;
        size = 0;
    }

    public synchronized void addFirst(E element) {
        ensureCapacity(size + 1);
        elements[head = (head - 1 + elements.length) % elements.length] = element;
        size++;
    }

    public synchronized E removeFirst() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        E element = elements[head];
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized void addLast(E element) {
        ensureCapacity(size + 1);
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
    }

    public synchronized E removeLast() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        E element = elements[tail - 1];
        tail = (tail - 1 + elements.length) % elements.length;
        size--;
        notifyAll();
        return element;
    }

    public synchronized E peekFirst() {
        if (size == 0) {
            return null;
        }
        return elements[head];
    }

    public synchronized E peekLast() {
        if (size == 0) {
            return null;
        }
        return elements[(tail - 1 + elements.length) % elements.length];
    }

    public synchronized int size() {
        return size;
    }

    private void ensureCapacity(int capacity) {
        if (capacity > elements.length) {
            int newCapacity = Math.max(elements.length << 1, capacity);
            E[] newElements = (E[]) new Object[newCapacity];
            for (int i = 0; i < size; i++) {
                newElements[i] = elements[(head + i) % elements.length];
            }
            head = 0;
            tail = size;
            elements = newElements;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < size; i++) {
            if (i != 0) sb.append(", ");
            sb.append(elements[(head + i) % elements.length]);
        }
        sb.append("]");
        return sb.toString();
    }
}
