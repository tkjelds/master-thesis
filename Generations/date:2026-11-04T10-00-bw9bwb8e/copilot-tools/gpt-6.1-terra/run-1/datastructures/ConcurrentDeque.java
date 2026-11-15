/**
 * A bounded, thread-safe deque. Null elements are not permitted.
 *
 * offer and poll operations return immediately: offer returns false when full,
 * and poll returns null when empty. put and take operations wait for space or
 * an element, respectively, and propagate InterruptedException if interrupted
 * while waiting. Waiting threads are not guaranteed a particular ordering.
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
            throw new IllegalArgumentException("capacity must be positive");
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

    public boolean offerFirst(E element) {
        return offer(element, true);
    }

    public boolean offerLast(E element) {
        return offer(element, false);
    }

    public void putFirst(E element) throws InterruptedException {
        put(element, true);
    }

    public void putLast(E element) throws InterruptedException {
        put(element, false);
    }

    public E pollFirst() {
        return poll(true);
    }

    public E pollLast() {
        return poll(false);
    }

    public E takeFirst() throws InterruptedException {
        return take(true);
    }

    public E takeLast() throws InterruptedException {
        return take(false);
    }

    public E peekFirst() {
        return peek(true);
    }

    public E peekLast() {
        return peek(false);
    }

    public void clear() {
        synchronized (lock) {
            if (size == 0) {
                return;
            }
            for (int i = 0; i < size; i++) {
                elements[indexAt(i)] = null;
            }
            head = 0;
            size = 0;
            lock.notifyAll();
        }
    }

    private boolean offer(E element, boolean first) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insert(element, first);
            return true;
        }
    }

    private void put(E element, boolean first) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insert(element, first);
        }
    }

    private E poll(boolean first) {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return remove(first);
        }
    }

    private E take(boolean first) throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return remove(first);
        }
    }

    private E peek(boolean first) {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return elementAt(first ? head : indexAt(size - 1));
        }
    }

    private void insert(E element, boolean first) {
        if (first) {
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = element;
        } else {
            elements[indexAt(size)] = element;
        }
        size++;
        lock.notifyAll();
    }

    private E remove(boolean first) {
        int index = first ? head : indexAt(size - 1);
        E element = elementAt(index);
        elements[index] = null;
        if (first) {
            head = head == elements.length - 1 ? 0 : head + 1;
        }
        size--;
        lock.notifyAll();
        return element;
    }

    private int indexAt(int offset) {
        int remaining = elements.length - head;
        return offset < remaining ? head + offset : offset - remaining;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }
}
