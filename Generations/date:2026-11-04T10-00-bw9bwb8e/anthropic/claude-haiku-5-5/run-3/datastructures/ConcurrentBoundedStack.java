@ConcurrentBoundedStack.ThreadSafe
public final class ConcurrentBoundedStack<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    /**
     * Creates a bounded, thread-safe LIFO stack.
     *
     * @param capacity maximum number of elements; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public ConcurrentBoundedStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    /**
     * Blocking push policy: if the stack is full, the calling thread waits
     * until space becomes available or the thread is interrupted.
     *
     * @param item non-null item to push
     * @throws NullPointerException if item is null
     * @throws InterruptedException if the waiting thread is interrupted
     *         (the stack is left unchanged)
     */
    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size] = item;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Blocking pop policy: if the stack is empty, the calling thread waits
     * until an element becomes available or the thread is interrupted.
     *
     * @return the most recently pushed element still present
     * @throws InterruptedException if the waiting thread is interrupted
     *         (the stack is left unchanged)
     */
    @SuppressWarnings("unchecked")
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            size--;
            T item = (T) elements[size];
            elements[size] = null;
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Non-blocking push policy: returns immediately.
     *
     * @param item non-null item to push
     * @return true if the item was pushed, false if the stack was full
     * @throws NullPointerException if item is null
     */
    public boolean tryPush(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[size] = item;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Non-blocking pop policy: returns immediately.
     *
     * @return the top element, or null if the stack was empty
     */
    @SuppressWarnings("unchecked")
    public T tryPop() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            size--;
            T item = (T) elements[size];
            elements[size] = null;
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Returns the top element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public T peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (T) elements[size - 1];
        }
    }

    /**
     * @return a consistent snapshot of the current number of elements
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * @return true if the stack holds no elements at the time of the call
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * @return true if the stack is at capacity at the time of the call
     */
    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    /**
     * @return the fixed maximum number of elements
     */
    public int capacity() {
        return elements.length;
    }
}
