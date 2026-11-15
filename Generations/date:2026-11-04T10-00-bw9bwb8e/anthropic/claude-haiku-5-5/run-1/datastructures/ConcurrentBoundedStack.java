/**
 * A thread-safe, bounded, blocking stack.
 *
 * Policy for operations that cannot complete immediately:
 * - push(item) blocks while the stack is full and resumes when space becomes available.
 * - pop() blocks while the stack is empty and resumes when an element becomes available.
 * - tryPush(item) never blocks; it returns false if the stack is full.
 * - tryPop() never blocks; it returns null if the stack is empty.
 * - Null elements are rejected with NullPointerException, so a null return from tryPop()
 *   unambiguously means "empty".
 * - Blocking operations throw InterruptedException if the calling thread is interrupted
 *   while waiting; the stack state is left unchanged in that case.
 *
 * All shared state is guarded by a single private monitor. Every read and write of the
 * shared fields happens inside a synchronized block on that monitor, which provides mutual
 * exclusion, visibility, and safe publication. Waits are guarded by while loops, and state
 * changes wake all waiters with notifyAll, since both producers and consumers share the
 * same monitor.
 */
@ConcurrentBoundedStack.ThreadSafe
public final class ConcurrentBoundedStack<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] elements;
    private int size; // guarded by lock

    public ConcurrentBoundedStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    /**
     * Pushes an element onto the top of the stack, blocking while the stack is full.
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
     * Removes and returns the top element, blocking while the stack is empty.
     */
    @SuppressWarnings("unchecked")
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            size--;
            T item = (T) elements[size];
            elements[size] = null; // avoid retaining references
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Attempts to push without blocking.
     *
     * @return true if the element was pushed, false if the stack was full
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
     * Attempts to pop without blocking.
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
     * Returns the top element without removing it, or null if the stack is empty.
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
     * Returns the number of elements currently in the stack.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the stack currently contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns true if the stack is currently at capacity.
     */
    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    /**
     * Returns the fixed maximum number of elements the stack can hold.
     */
    public int capacity() {
        return elements.length;
    }
}
