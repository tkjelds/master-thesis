/**
 * A bounded, thread-safe deque. Null elements are not permitted.
 *
 * offer and poll operations return immediately when the deque is full or
 * empty, respectively. put and take operations wait until they can proceed
 * and may be interrupted.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int head;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
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

    public boolean offerFirst(E element) {
        requireElement(element);
        synchronized (monitor) {
            if (size == elements.length) {
                return false;
            }
            insertFirst(element);
            monitor.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        requireElement(element);
        synchronized (monitor) {
            if (size == elements.length) {
                return false;
            }
            insertLast(element);
            monitor.notifyAll();
            return true;
        }
    }

    public void putFirst(E element) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }
            insertFirst(element);
            monitor.notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }
            insertLast(element);
            monitor.notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E result = extractFirst();
            monitor.notifyAll();
            return result;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E result = extractLast();
            monitor.notifyAll();
            return result;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E result = extractFirst();
            monitor.notifyAll();
            return result;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E result = extractLast();
            monitor.notifyAll();
            return result;
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(index(size - 1));
        }
    }

    public void clear() {
        synchronized (monitor) {
            if (size == 0) {
                return;
            }
            for (int i = 0; i < size; i++) {
                elements[index(i)] = null;
            }
            head = 0;
            size = 0;
            monitor.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // The following helpers are called only while holding monitor.
    private int index(int offset) {
        int untilEnd = elements.length - head;
        return offset >= untilEnd ? offset - untilEnd : head + offset;
    }

    private void insertFirst(E element) {
        head = head == 0 ? elements.length - 1 : head - 1;
        elements[head] = element;
        size++;
    }

    private void insertLast(E element) {
        elements[index(size)] = element;
        size++;
    }

    private E extractFirst() {
        E result = elementAt(head);
        elements[head] = null;
        size--;
        if (size == 0) {
            head = 0;
        } else {
            head = head == elements.length - 1 ? 0 : head + 1;
        }
        return result;
    }

    private E extractLast() {
        int last = index(size - 1);
        E result = elementAt(last);
        elements[last] = null;
        size--;
        if (size == 0) {
            head = 0;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
