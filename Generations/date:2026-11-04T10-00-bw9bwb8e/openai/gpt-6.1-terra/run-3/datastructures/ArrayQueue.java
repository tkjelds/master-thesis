/**
 * A bounded, thread-safe FIFO queue backed by a circular array.
 *
 * Null elements are not permitted. offer returns false when full, while put
 * waits for space. poll and peek return null when empty, while take waits
 * for an element. Waiting operations may be interrupted.
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
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public int capacity() {
        return elements.length;
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

    public boolean offer(E element) {
        requireElement(element);
        synchronized (monitor) {
            if (size == elements.length) {
                return false;
            }
            insert(element);
            monitor.notifyAll();
            return true;
        }
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }
            insert(element);
            monitor.notifyAll();
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E result = remove();
            monitor.notifyAll();
            return result;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E result = remove();
            monitor.notifyAll();
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (monitor) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    public void clear() {
        synchronized (monitor) {
            if (size == 0) {
                return;
            }
            while (size > 0) {
                elements[head] = null;
                head = next(head);
                size--;
            }
            head = 0;
            tail = 0;
            monitor.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }

    // The following helpers are called only while holding monitor.
    private void insert(E element) {
        elements[tail] = element;
        tail = next(tail);
        size++;
    }

    @SuppressWarnings("unchecked")
    private E remove() {
        E result = (E) elements[head];
        elements[head] = null;
        head = next(head);
        size--;
        return result;
    }

    private int next(int index) {
        int nextIndex = index + 1;
        return nextIndex == elements.length ? 0 : nextIndex;
    }
}
