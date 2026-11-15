/**
 * A bounded, thread-safe FIFO queue backed by a circular array.
 *
 * Null elements are not permitted. offer returns false when full; poll and peek
 * return null when empty. put/enqueue and take/dequeue block until they can
 * complete, or throw InterruptedException without modifying the queue.
 *
 * All mutable state is protected by a private monitor. Waiting threads are
 * notified whenever the queue changes. No fairness guarantee is provided.
 */
@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue() {
        this(16);
    }

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (monitor) {
            if (count == elements.length) {
                return false;
            }
            insert(element);
            return true;
        }
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (monitor) {
            while (count == elements.length) {
                monitor.wait();
            }
            insert(element);
        }
    }

    public void enqueue(E element) throws InterruptedException {
        put(element);
    }

    public E poll() {
        synchronized (monitor) {
            return count == 0 ? null : remove();
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }
            return remove();
        }
    }

    public E dequeue() throws InterruptedException {
        return take();
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (monitor) {
            return count == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public int capacity() {
        synchronized (monitor) {
            return elements.length;
        }
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

    public void clear() {
        synchronized (monitor) {
            while (count > 0) {
                elements[head] = null;
                head = advance(head);
                count--;
            }
            head = 0;
            tail = 0;
            monitor.notifyAll();
        }
    }

    // Called only while holding monitor.
    private void insert(E element) {
        elements[tail] = element;
        tail = advance(tail);
        count++;
        monitor.notifyAll();
    }

    // Called only while holding monitor.
    @SuppressWarnings("unchecked")
    private E remove() {
        E element = (E) elements[head];
        elements[head] = null;
        head = advance(head);
        count--;
        monitor.notifyAll();
        return element;
    }

    private int advance(int index) {
        return index + 1 == elements.length ? 0 : index + 1;
    }
}
