@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<E> {
    @interface ThreadSafe {}


    private final Object lock;
    private final E[] elements;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.lock = new Object();
        this.capacity = capacity;
        this.elements = (E[]) new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    public int capacity() {
        return capacity;
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
            return size == capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                elements[i] = null;
            }
            head = 0;
            tail = 0;
            size = 0;
            lock.notifyAll();
        }
    }

    public void addFirst(E e) {
        insertFirst(e, false, false);
    }

    public void addLast(E e) {
        insertLast(e, false, false);
    }

    public boolean offerFirst(E e) {
        return insertFirst(e, false, true);
    }

    public boolean offerLast(E e) {
        return insertLast(e, false, true);
    }

    public void putFirst(E e) {
        insertFirst(e, true, false);
    }

    public void putLast(E e) {
        insertLast(e, true, false);
    }

    public E removeFirst() {
        return removeFirst(false, false);
    }

    public E removeLast() {
        return removeLast(false, false);
    }

    public E pollFirst() {
        return removeFirst(false, true);
    }

    public E pollLast() {
        return removeLast(false, true);
    }

    public E takeFirst() {
        return removeFirst(true, true);
    }

    public E takeLast() {
        return removeLast(true, true);
    }

    public E getFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new DequeEmptyException("Deque is empty");
            }
            return elements[head];
        }
    }

    public E getLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new DequeEmptyException("Deque is empty");
            }
            int index = (tail - 1 + capacity) % capacity;
            return elements[index];
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return elements[head];
        }
    }

    public E peekLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = (tail - 1 + capacity) % capacity;
            return elements[index];
        }
    }

    public E element() {
        return getFirst();
    }

    public E peek() {
        return peekFirst();
    }

    private boolean insertFirst(E e, boolean block, boolean returnFalseOnFull) {
        if (e == null) {
            throw new NullPointerException("value is null");
        }

        synchronized (lock) {
            while (size == capacity) {
                if (!block) {
                    if (returnFalseOnFull) {
                        return false;
                    }
                    throw new DequeFullException("Deque is full");
                }
                try {
                    lock.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to insert at front", ie);
                }
            }

            head = (head - 1 + capacity) % capacity;
            elements[head] = e;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    private boolean insertLast(E e, boolean block, boolean returnFalseOnFull) {
        if (e == null) {
            throw new NullPointerException("value is null");
        }

        synchronized (lock) {
            while (size == capacity) {
                if (!block) {
                    if (returnFalseOnFull) {
                        return false;
                    }
                    throw new DequeFullException("Deque is full");
                }
                try {
                    lock.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to insert at rear", ie);
                }
            }

            elements[tail] = e;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    private E removeFirst(boolean block, boolean returnNullOnEmpty) {
        synchronized (lock) {
            while (size == 0) {
                if (!block) {
                    if (returnNullOnEmpty) {
                        return null;
                    }
                    throw new DequeEmptyException("Deque is empty");
                }
                try {
                    lock.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to remove from front", ie);
                }
            }

            E value = elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return value;
        }
    }

    private E removeLast(boolean block, boolean returnNullOnEmpty) {
        synchronized (lock) {
            while (size == 0) {
                if (!block) {
                    if (returnNullOnEmpty) {
                        return null;
                    }
                    throw new DequeEmptyException("Deque is empty");
                }
                try {
                    lock.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to remove from rear", ie);
                }
            }

            int index = (tail - 1 + capacity) % capacity;
            E value = elements[index];
            elements[index] = null;
            tail = index;
            size--;
            lock.notifyAll();
            return value;
        }
    }

    public static class DequeEmptyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DequeEmptyException() {
            super();
        }

        public DequeEmptyException(String message) {
            super(message);
        }
    }

    public static class DequeFullException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DequeFullException() {
            super();
        }

        public DequeFullException(String message) {
            super(message);
        }
    }
}
