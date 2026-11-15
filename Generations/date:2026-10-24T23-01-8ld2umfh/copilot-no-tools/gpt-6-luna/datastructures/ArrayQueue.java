@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            monitor.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E element = removeHead();
            monitor.notifyAll();
            return element;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E element = removeHead();
            monitor.notifyAll();
            return element;
        }
    }

    public E peek() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            return elementAt(head);
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int capacity() {
        synchronized (monitor) {
            return capacity;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private E removeHead() {
        E element = elementAt(head);
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return element;
    }
}
