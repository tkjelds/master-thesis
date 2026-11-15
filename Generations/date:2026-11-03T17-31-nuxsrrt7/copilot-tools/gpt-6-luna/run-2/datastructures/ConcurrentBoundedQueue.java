@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            lock.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = elementAt(head);
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1) % elements.length;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = elementAt(head);
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
