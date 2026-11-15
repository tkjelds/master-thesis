@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int first;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public int capacity() {
        synchronized (monitor) {
            return elements.length;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return elements.length - size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean offerFirst(E element) {
        requireNonNull(element);
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
        requireNonNull(element);
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
        requireNonNull(element);
        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }
            insertFirst(element);
            monitor.notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireNonNull(element);
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
            E element = removeFirst();
            monitor.notifyAll();
            return element;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E element = removeLast();
            monitor.notifyAll();
            return element;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E element = removeFirst();
            monitor.notifyAll();
            return element;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E element = removeLast();
            monitor.notifyAll();
            return element;
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(0);
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(size - 1);
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                elements[indexAt(i)] = null;
            }
            first = 0;
            size = 0;
            monitor.notifyAll();
        }
    }

    private void insertFirst(E element) {
        first = first == 0 ? elements.length - 1 : first - 1;
        elements[first] = element;
        size++;
    }

    private void insertLast(E element) {
        elements[indexAt(size)] = element;
        size++;
    }

    @SuppressWarnings("unchecked")
    private E removeFirst() {
        E element = (E) elements[first];
        elements[first] = null;
        first = first + 1 == elements.length ? 0 : first + 1;
        size--;
        if (size == 0) {
            first = 0;
        }
        return element;
    }

    @SuppressWarnings("unchecked")
    private E removeLast() {
        int index = indexAt(size - 1);
        E element = (E) elements[index];
        elements[index] = null;
        size--;
        if (size == 0) {
            first = 0;
        }
        return element;
    }

    private int indexAt(int offset) {
        int toEnd = elements.length - first;
        return offset < toEnd ? first + offset : offset - toEnd;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int offset) {
        return (E) elements[indexAt(offset)];
    }

    private void requireNonNull(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }
}
