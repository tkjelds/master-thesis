@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    // Blocks while the bounded queue is full.
    public void enqueue(E value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("null elements are not supported");
        }

        synchronized (monitor) {
            while (count == elements.length) {
                monitor.wait();
            }
            elements[tail] = value;
            tail = (tail + 1) % elements.length;
            count++;
            monitor.notifyAll();
        }
    }

    // Returns false immediately if the bounded queue is full.
    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("null elements are not supported");
        }

        synchronized (monitor) {
            if (count == elements.length) {
                return false;
            }
            elements[tail] = value;
            tail = (tail + 1) % elements.length;
            count++;
            monitor.notifyAll();
            return true;
        }
    }

    // Blocks while the queue is empty.
    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }
            return removeHead();
        }
    }

    // Returns null immediately if the queue is empty.
    public E poll() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            return removeHead();
        }
    }

    // Returns the head without removing it, or null if the queue is empty.
    public E peek() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E value = (E) elements[head];
            return value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return elements.length - count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return count == elements.length;
        }
    }

    private E removeHead() {
        @SuppressWarnings("unchecked")
        E value = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        monitor.notifyAll();
        return value;
    }
}
