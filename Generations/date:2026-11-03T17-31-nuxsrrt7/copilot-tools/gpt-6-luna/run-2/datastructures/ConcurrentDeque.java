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

    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return elements.length - size;
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
            if (size == 0) {
                return null;
            }
            return elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return elementAt((head + size - 1) % elements.length);
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[(head + i) % elements.length] = null;
            }
            head = 0;
            size = 0;
            lock.notifyAll();
        }
    }

    private void insertFirst(E element) {
        head = (head - 1 + elements.length) % elements.length;
        elements[head] = element;
        size++;
    }

    private void insertLast(E element) {
        elements[(head + size) % elements.length] = element;
        size++;
    }

    private E removeFirst() {
        E element = elementAt(head);
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        if (size == 0) {
            head = 0;
        }
        return element;
    }

    private E removeLast() {
        int index = (head + size - 1) % elements.length;
        E element = elementAt(index);
        elements[index] = null;
        size--;
        if (size == 0) {
            head = 0;
        }
        return element;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }
    }
}
