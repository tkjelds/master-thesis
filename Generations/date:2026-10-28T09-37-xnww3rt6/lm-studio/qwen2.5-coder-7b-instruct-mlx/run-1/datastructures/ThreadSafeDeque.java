@ThreadSafeDeque.ThreadSafe
public class ThreadSafeDeque<E> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int head, tail, size;

    public ThreadSafeDeque(int capacity) {
        elements = new Object[capacity];
        head = tail = size = 0;
    }

    public synchronized void addFirst(E e) {
        ensureCapacity();
        elements[head = (head - 1 + elements.length) % elements.length] = e;
        size++;
        notifyAll();
    }

    public synchronized void addLast(E e) {
        ensureCapacity();
        elements[tail] = e;
        tail = (tail + 1) % elements.length;
        size++;
        notifyAll();
    }

    public synchronized E removeFirst() throws InterruptedException {
        while (size == 0) wait();
        E e = (E) elements[head];
        head = (head + 1) % elements.length;
        size--;
        notifyAll();
        return e;
    }

    public synchronized E removeLast() throws InterruptedException {
        while (size == 0) wait();
        tail = (tail - 1 + elements.length) % elements.length;
        E e = (E) elements[tail];
        size--;
        notifyAll();
        return e;
    }

    public synchronized E getFirst() throws InterruptedException {
        while (size == 0) wait();
        return (E) elements[head];
    }

    public synchronized E getLast() throws InterruptedException {
        while (size == 0) wait();
        return (E) elements[(tail - 1 + elements.length) % elements.length];
    }

    private synchronized void ensureCapacity() {
        if (size == elements.length) {
            Object[] newElements = new Object[elements.length * 2];
            int readIndex = head;
            for (int writeIndex = 0; writeIndex < size; writeIndex++) {
                newElements[writeIndex] = elements[readIndex];
                readIndex = (readIndex + 1) % elements.length;
            }
            elements = newElements;
            head = 0;
            tail = size;
        }
    }
}
