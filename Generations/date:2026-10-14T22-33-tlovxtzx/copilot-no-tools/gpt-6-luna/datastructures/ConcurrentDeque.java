/**
 * A fixed-capacity, thread-safe blocking deque. putFirst/putLast wait while full;
 * takeFirst/takeLast wait while empty. offer and poll operations never wait.
 * Null elements are not permitted.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void putFirst(E element) throws InterruptedException {
        requireElement(element);
        synchronized (this) {
            while (size == elements.length) {
                wait();
            }
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = element;
            size++;
            notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireElement(element);
        synchronized (this) {
            while (size == elements.length) {
                wait();
            }
            elements[tail] = element;
            tail = increment(tail);
            size++;
            notifyAll();
        }
    }

    public boolean offerFirst(E element) {
        requireElement(element);
        synchronized (this) {
            if (size == elements.length) {
                return false;
            }
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = element;
            size++;
            notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        requireElement(element);
        synchronized (this) {
            if (size == elements.length) {
                return false;
            }
            elements[tail] = element;
            tail = increment(tail);
            size++;
            notifyAll();
            return true;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E element = elementAt(head);
            elements[head] = null;
            head = increment(head);
            size--;
            notifyAll();
            return element;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            tail = tail == 0 ? elements.length - 1 : tail - 1;
            E element = elementAt(tail);
            elements[tail] = null;
            size--;
            notifyAll();
            return element;
        }
    }

    public E pollFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E element = elementAt(head);
            elements[head] = null;
            head = increment(head);
            size--;
            notifyAll();
            return element;
        }
    }

    public E pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            tail = tail == 0 ? elements.length - 1 : tail - 1;
            E element = elementAt(tail);
            elements[tail] = null;
            size--;
            notifyAll();
            return element;
        }
    }

    public E peekFirst() {
        synchronized (this) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            int last = tail == 0 ? elements.length - 1 : tail - 1;
            return elementAt(last);
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (this) {
            return elements.length - size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == elements.length;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < elements.length; i++) {
                elements[i] = null;
            }
            head = 0;
            tail = 0;
            size = 0;
            notifyAll();
        }
    }

    private int increment(int index) {
        return index + 1 == elements.length ? 0 : index + 1;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
