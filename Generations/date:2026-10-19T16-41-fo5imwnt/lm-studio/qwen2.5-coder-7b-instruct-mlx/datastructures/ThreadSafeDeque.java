@ThreadSafeDeque.ThreadSafe
public class ThreadSafeDeque<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ThreadSafeDeque(int capacity) {
        elements = new Object[capacity];
        head = 0;
        tail = -1;
        count = 0;
    }

    public synchronized void addFirst(E e) {
        if (count == elements.length) {
            throw new IllegalStateException("Deque is full");
        }
        head = (head - 1 + elements.length) % elements.length;
        elements[head] = e;
        count++;
        notifyAll();
    }

    public synchronized E removeFirst() {
        if (count == 0) {
            throw new IllegalStateException("Deque is empty");
        }
        E result = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        notifyAll();
        return result;
    }

    public synchronized void addLast(E e) {
        if (count == elements.length) {
            throw new IllegalStateException("Deque is full");
        }
        tail = (tail + 1) % elements.length;
        elements[tail] = e;
        count++;
        notifyAll();
    }

    public synchronized E removeLast() {
        if (count == 0) {
            throw new IllegalStateException("Deque is empty");
        }
        tail = (tail - 1 + elements.length) % elements.length;
        E result = (E) elements[tail];
        elements[tail] = null;
        count--;
        notifyAll();
        return result;
    }

    public synchronized E peekFirst() {
        if (count == 0) {
            throw new IllegalStateException("Deque is empty");
        }
        return (E) elements[head];
    }

    public synchronized E peekLast() {
        if (count == 0) {
            throw new IllegalStateException("Deque is empty");
        }
        return (E) elements[tail];
    }

    public synchronized int size() {
        return count;
    }

    public synchronized boolean isEmpty() {
        return count == 0;
    }
}
