@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

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

    public int capacity() {
        return capacity;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == capacity;
        }
    }

    public boolean offerFirst(E element) {
        requireNonNull(element);
        synchronized (this) {
            if (size == capacity) {
                return false;
            }
            insertFirst(element);
            notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        requireNonNull(element);
        synchronized (this) {
            if (size == capacity) {
                return false;
            }
            insertLast(element);
            notifyAll();
            return true;
        }
    }

    public void putFirst(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            insertFirst(element);
            notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            insertLast(element);
            notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E element = removeFirst();
            notifyAll();
            return element;
        }
    }

    public E pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E element = removeLast();
            notifyAll();
            return element;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E element = removeFirst();
            notifyAll();
            return element;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E element = removeLast();
            notifyAll();
            return element;
        }
    }

    public E peekFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            return elementAt(addIndex(head, size - 1));
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                elements[addIndex(head, i)] = null;
            }
            head = 0;
            size = 0;
            notifyAll();
        }
    }

    private void insertFirst(E element) {
        head = head == 0 ? capacity - 1 : head - 1;
        elements[head] = element;
        size++;
    }

    private void insertLast(E element) {
        elements[addIndex(head, size)] = element;
        size++;
    }

    private E removeFirst() {
        E element = elementAt(head);
        elements[head] = null;
        head = head + 1 == capacity ? 0 : head + 1;
        size--;
        if (size == 0) {
            head = 0;
        }
        return element;
    }

    private E removeLast() {
        int last = addIndex(head, size - 1);
        E element = elementAt(last);
        elements[last] = null;
        size--;
        if (size == 0) {
            head = 0;
        }
        return element;
    }

    private int addIndex(int index, int offset) {
        int remaining = capacity - index;
        return offset >= remaining ? offset - remaining : index + offset;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void requireNonNull(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }
}
