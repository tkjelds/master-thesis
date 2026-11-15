@ConcurrentDeque.ThreadSafe
class ConcurrentDeque {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head, tail;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public synchronized boolean addFirst(Object element) {
        if (element == null) {
            throw new NullPointerException();
        }
        if (isFull()) {
            return false;
        }
        elements[head = ((head - 1 + elements.length) & (elements.length - 1))] = element;
        notifyAll();
        return true;
    }

    public synchronized boolean addLast(Object element) {
        if (element == null) {
            throw new NullPointerException();
        }
        if (isFull()) {
            return false;
        }
        elements[tail = ((tail + 1) & (elements.length - 1))] = element;
        notifyAll();
        return true;
    }

    public synchronized Object removeFirst() {
        if (isEmpty()) {
            throw new IllegalStateException("Deque is empty");
        }
        Object result = elements[head];
        elements[head] = null;
        head = ((head + 1) & (elements.length - 1));
        notifyAll();
        return result;
    }

    public synchronized Object removeLast() {
        if (isEmpty()) {
            throw new IllegalStateException("Deque is empty");
        }
        tail = ((tail - 1 + elements.length) & (elements.length - 1));
        Object result = elements[tail];
        elements[tail] = null;
        notifyAll();
        return result;
    }

    public synchronized Object getFirst() {
        if (isEmpty()) {
            throw new IllegalStateException("Deque is empty");
        }
        return elements[head];
    }

    public synchronized Object getLast() {
        if (isEmpty()) {
            throw new IllegalStateException("Deque is empty");
        }
        return elements[tail];
    }

    public synchronized boolean isEmpty() {
        return head == tail;
    }

    public synchronized boolean isFull() {
        return ((tail - head + elements.length) & (elements.length - 1)) == elements.length - 1;
    }

    public synchronized int size() {
        return ((tail - head + elements.length) & (elements.length - 1));
    }
}
