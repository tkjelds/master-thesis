@ConcurrentBoundedStack.ThreadSafe
@SuppressWarnings("unchecked")
public final class ConcurrentBoundedStack<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    /**
     * Creates an empty bounded stack with the given fixed capacity.
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
     * Pushes an item, blocking while the stack is full.
     * Blocked callers are woken when a pop or successful push changes the state.
     *
     * @param item non-null item to push
     * @throws NullPointerException if item is null
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null items are not permitted");
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
     * Pops the top item, blocking while the stack is empty.
     *
     * @return the item at the top of the stack
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            size--;
            Object item = elements[size];
            elements[size] = null;
            lock.notifyAll();
            return (T) item;
        }
    }

    /**
     * Non-blocking push.
     *
     * @param item non-null item to push
     * @return true if the item was pushed; false if the stack was full
     * @throws NullPointerException if item is null
     */
    public boolean tryPush(T item) {
        if (item == null) {
            throw new NullPointerException("null items are not permitted");
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
     * Non-blocking pop.
     *
     * @return the top item, or null if the stack was empty
     */
    public T tryPop() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            size--;
            Object item = elements[size];
            elements[size] = null;
            lock.notifyAll();
            return (T) item;
        }
    }

    /**
     * Returns the top item without removing it, or null if the stack is empty.
     *
     * @return the top item, or null
     */
    public T peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (T) elements[size - 1];
        }
    }

    /**
     * @return the number of elements currently in the stack
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * @return true if the stack currently holds no elements
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * @return true if the stack is currently at capacity
     */
    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    /**
     * @return the fixed maximum capacity of this stack
     */
    public int capacity() {
        return elements.length;
    }
}
