/**
 * A thread-safe, bounded FIFO queue backed by a circular array.
 *
 * Null elements are not permitted. Operations never wait for space or data:
 * enqueue returns false when full; dequeue and peek return null when empty.
 */
@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
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

    /**
     * Appends an element, returning false without changing the queue if full.
     */
    public boolean enqueue(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            if (size == elements.length) {
                return false;
            }

            elements[tail] = element;
            tail = nextIndex(tail);
            size++;
            return true;
        }
    }

    /**
     * Removes and returns the oldest element, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E dequeue() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = nextIndex(head);
            size--;
            return element;
        }
    }

    /**
     * Returns the oldest element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == elements.length;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public void clear() {
        synchronized (monitor) {
            while (size > 0) {
                elements[head] = null;
                head = nextIndex(head);
                size--;
            }
            head = 0;
            tail = 0;
        }
    }

    private int nextIndex(int index) {
        return index == elements.length - 1 ? 0 : index + 1;
    }
}
