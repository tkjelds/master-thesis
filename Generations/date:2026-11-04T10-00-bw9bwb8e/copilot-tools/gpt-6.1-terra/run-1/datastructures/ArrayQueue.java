/**
 * A bounded, thread-safe FIFO queue that does not permit null elements.
 *
 * put and take block while the queue is full or empty, respectively.
 * offer and poll return immediately when their operation cannot complete.
 */
@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            enqueue(element);
        }
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            enqueue(element);
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return dequeue();
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return dequeue();
        }
    }

    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : headElement();
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
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

    public int capacity() {
        return elements.length;
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
    }

    // The following helpers require lock to be held.
    private void enqueue(E element) {
        elements[tail] = element;
        tail = (tail + 1 == elements.length) ? 0 : tail + 1;
        size++;
        lock.notifyAll();
    }

    private E dequeue() {
        E element = headElement();
        elements[head] = null;
        head = (head + 1 == elements.length) ? 0 : head + 1;
        size--;
        lock.notifyAll();
        return element;
    }

    @SuppressWarnings("unchecked")
    private E headElement() {
        return (E) elements[head];
    }
}
