@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    /**
     * Creates a bounded deque. Null elements are not permitted.
     */
    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /**
     * Returns false immediately if the deque is full.
     */
    public boolean offerFirst(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insertFirst(element);
            return true;
        }
    }

    /**
     * Returns false immediately if the deque is full.
     */
    public boolean offerLast(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insertLast(element);
            return true;
        }
    }

    /**
     * Waits for space if the deque is full.
     */
    public void putFirst(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insertFirst(element);
        }
    }

    /**
     * Waits for space if the deque is full.
     */
    public void putLast(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insertLast(element);
        }
    }

    /**
     * Returns null immediately if the deque is empty.
     */
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeFirst();
        }
    }

    /**
     * Returns null immediately if the deque is empty.
     */
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeLast();
        }
    }

    /**
     * Waits for an element if the deque is empty.
     */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeFirst();
        }
    }

    /**
     * Waits for an element if the deque is empty.
     */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeLast();
        }
    }

    /**
     * Returns null if the deque is empty.
     */
    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    /**
     * Returns null if the deque is empty.
     */
    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[previous(tail)];
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

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return elements.length - size;
        }
    }

    public void clear() {
        synchronized (lock) {
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
            lock.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    private int next(int index) {
        return index == elements.length - 1 ? 0 : index + 1;
    }

    private int previous(int index) {
        return index == 0 ? elements.length - 1 : index - 1;
    }

    // The following helpers are called only while holding lock.
    private void insertFirst(E element) {
        head = previous(head);
        elements[head] = element;
        size++;
        lock.notifyAll();
    }

    private void insertLast(E element) {
        elements[tail] = element;
        tail = next(tail);
        size++;
        lock.notifyAll();
    }

    @SuppressWarnings("unchecked")
    private E removeFirst() {
        E element = (E) elements[head];
        elements[head] = null;
        head = next(head);
        size--;
        lock.notifyAll();
        return element;
    }

    @SuppressWarnings("unchecked")
    private E removeLast() {
        tail = previous(tail);
        E element = (E) elements[tail];
        elements[tail] = null;
        size--;
        lock.notifyAll();
        return element;
    }
}
