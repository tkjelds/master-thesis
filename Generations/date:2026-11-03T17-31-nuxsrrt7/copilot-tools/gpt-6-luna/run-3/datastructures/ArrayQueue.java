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
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void enqueue(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not allowed");
        }

        synchronized (monitor) {
            while (count == elements.length) {
                monitor.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            count++;
            monitor.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }
            E element = removeHead();
            monitor.notifyAll();
            return element;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not allowed");
        }

        synchronized (monitor) {
            if (count == elements.length) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            count++;
            monitor.notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            E element = removeHead();
            monitor.notifyAll();
            return element;
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

    @SuppressWarnings("unchecked")
    private E removeHead() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        return element;
    }
}
