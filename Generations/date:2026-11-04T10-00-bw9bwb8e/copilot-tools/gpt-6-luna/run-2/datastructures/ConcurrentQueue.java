@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not allowed");
        }
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            enqueue(element);
            notifyAll();
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not allowed");
        }
        synchronized (this) {
            if (size == capacity) {
                return false;
            }
            enqueue(element);
            notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E element = dequeue();
            notifyAll();
            return element;
        }
    }

    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E element = dequeue();
            notifyAll();
            return element;
        }
    }

    public E peek() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return elementAt(head);
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == capacity;
        }
    }

    private void enqueue(E element) {
        elements[tail] = element;
        tail = (tail + 1) % capacity;
        size++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return element;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
