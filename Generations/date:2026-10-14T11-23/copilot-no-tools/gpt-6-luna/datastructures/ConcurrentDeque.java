public final class ConcurrentDeque<E> {
    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public boolean offerFirst(E element) {
        requireNonNull(element);
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            head = (head == 0) ? capacity - 1 : head - 1;
            elements[head] = element;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        requireNonNull(element);
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            elements[tail] = element;
            tail = (tail + 1 == capacity) ? 0 : tail + 1;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    public void putFirst(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            head = (head == 0) ? capacity - 1 : head - 1;
            elements[head] = element;
            count++;
            lock.notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireNonNull(element);
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            elements[tail] = element;
            tail = (tail + 1 == capacity) ? 0 : tail + 1;
            count++;
            lock.notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            Object element = elements[head];
            elements[head] = null;
            head = (head + 1 == capacity) ? 0 : head + 1;
            count--;
            lock.notifyAll();
            return cast(element);
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            tail = (tail == 0) ? capacity - 1 : tail - 1;
            Object element = elements[tail];
            elements[tail] = null;
            count--;
            lock.notifyAll();
            return cast(element);
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            Object element = elements[head];
            elements[head] = null;
            head = (head + 1 == capacity) ? 0 : head + 1;
            count--;
            lock.notifyAll();
            return cast(element);
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            tail = (tail == 0) ? capacity - 1 : tail - 1;
            Object element = elements[tail];
            elements[tail] = null;
            count--;
            lock.notifyAll();
            return cast(element);
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return count == 0 ? null : cast(elements[head]);
        }
    }

    public E peekLast() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            int last = (tail == 0) ? capacity - 1 : tail - 1;
            return cast(elements[last]);
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - count;
        }
    }

    private static void requireNonNull(Object element) {
        if (element == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }

    @SuppressWarnings("unchecked")
    private E cast(Object element) {
        return (E) element;
    }
}
