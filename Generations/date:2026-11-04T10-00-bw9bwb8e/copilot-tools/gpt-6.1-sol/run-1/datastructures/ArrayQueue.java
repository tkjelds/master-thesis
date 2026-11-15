/**
 * A bounded, thread-safe FIFO queue backed by a fixed-size array.
 * Null elements are not permitted.
 *
 * offer returns false when full; poll and peek return null when empty.
 * put and take block interruptibly until space or an element is available.
 */
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

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (monitor) {
            if (count == elements.length) {
                return false;
            }
            enqueue(element);
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
            enqueue(element);
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            return dequeue();
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }
            return dequeue();
        }
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

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
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

    public void clear() {
        synchronized (monitor) {
            while (count > 0) {
                elements[head] = null;
                head = nextIndex(head);
                count--;
            }
            head = 0;
            tail = 0;
            monitor.notifyAll();
        }
    }

    // Caller must hold monitor and ensure the queue is not full.
    private void enqueue(E element) {
        elements[tail] = element;
        tail = nextIndex(tail);
        count++;
        monitor.notifyAll();
    }

    // Caller must hold monitor and ensure the queue is not empty.
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) elements[head];
        elements[head] = null;
        head = nextIndex(head);
        count--;
        monitor.notifyAll();
        return element;
    }

    private int nextIndex(int index) {
        return index == elements.length - 1 ? 0 : index + 1;
    }
}
