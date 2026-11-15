/**
 * A bounded, thread-safe FIFO array queue.
 *
 * enqueue and dequeue block while the queue is full or empty, respectively.
 * offer and poll return immediately; poll and peek return null when empty.
 * Null elements are not permitted.
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
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void enqueue(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insert(element);
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return remove();
        }
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insert(element);
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return remove();
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[head];
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
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Called only while holding lock.
    private void insert(E element) {
        elements[tail] = element;
        tail++;
        if (tail == elements.length) {
            tail = 0;
        }
        size++;
        lock.notifyAll();
    }

    // Called only while holding lock.
    @SuppressWarnings("unchecked")
    private E remove() {
        E element = (E) elements[head];
        elements[head] = null;
        head++;
        if (head == elements.length) {
            head = 0;
        }
        size--;
        lock.notifyAll();
        return element;
    }
}
