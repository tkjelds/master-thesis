/**
 * A bounded, thread-safe deque. Put and take operations wait for space or
 * an element, respectively; offer and poll operations return immediately.
 * Null elements are not permitted.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
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
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void putFirst(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insertFirst(element);
            lock.notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insertLast(element);
            lock.notifyAll();
        }
    }

    public boolean offerFirst(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insertFirst(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insertLast(element);
            lock.notifyAll();
            return true;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = removeFirst();
            lock.notifyAll();
            return element;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = removeLast();
            lock.notifyAll();
            return element;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = removeFirst();
            lock.notifyAll();
            return element;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = removeLast();
            lock.notifyAll();
            return element;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(index(size - 1));
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size == 0) {
                return;
            }
            while (size > 0) {
                removeFirst();
            }
            head = 0;
            lock.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    private int index(int offset) {
        int remaining = elements.length - head;
        return offset >= remaining ? offset - remaining : head + offset;
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

    private E removeFirst() {
        E element = elementAt(head);
        elements[head] = null;
        head = head == elements.length - 1 ? 0 : head + 1;
        size--;
        return element;
    }

    private E removeLast() {
        int last = index(size - 1);
        E element = elementAt(last);
        elements[last] = null;
        size--;
        return element;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
