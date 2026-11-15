@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public boolean offerFirst(E element) {
        checkElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            head = previousIndex(head);
            elements[head] = element;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        checkElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            elements[indexAtOffset(head, size)] = element;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public void putFirst(E element) throws InterruptedException {
        checkElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            head = previousIndex(head);
            elements[head] = element;
            size++;
            monitor.notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        checkElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            elements[indexAtOffset(head, size)] = element;
            size++;
            monitor.notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E element = elementAt(head);
            elements[head] = null;
            head = nextIndex(head);
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            int last = indexAtOffset(head, size - 1);
            E element = elementAt(last);
            elements[last] = null;
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E element = elementAt(head);
            elements[head] = null;
            head = nextIndex(head);
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            int last = indexAtOffset(head, size - 1);
            E element = elementAt(last);
            elements[last] = null;
            size--;
            monitor.notifyAll();
            return element;
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return size == 0 ? null : elementAt(indexAtOffset(head, size - 1));
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int capacity() {
        synchronized (monitor) {
            return capacity;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    private void checkElement(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }

    private int nextIndex(int index) {
        return index + 1 == capacity ? 0 : index + 1;
    }

    private int previousIndex(int index) {
        return index == 0 ? capacity - 1 : index - 1;
    }

    private int indexAtOffset(int start, int offset) {
        int untilEnd = capacity - start;
        return offset >= untilEnd ? offset - untilEnd : start + offset;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
