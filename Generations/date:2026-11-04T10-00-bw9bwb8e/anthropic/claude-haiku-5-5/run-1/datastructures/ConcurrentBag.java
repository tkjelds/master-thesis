@ConcurrentBag.ThreadSafe
@SuppressWarnings("unchecked")
public final class ConcurrentBag<T> {
    @interface ThreadSafe {}


    // Single monitor guarding all mutable state.
    private final Object lock = new Object();

    // Fixed-capacity storage. Only accessed while holding 'lock'.
    private final Object[] elements;

    // Number of elements currently stored. Only accessed while holding 'lock'.
    private int size;

    /**
     * Creates an empty bag with the given fixed capacity.
     *
     * Policy:
     *  - add(...) blocks while the bag is full.
     *  - remove() blocks while the bag is empty.
     *  - tryAdd(...) and tryRemove() never block; they return a failure indication instead.
     *  - Null elements are rejected with NullPointerException.
     *  - Blocking operations throw InterruptedException if the calling thread is interrupted.
     */
    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    /**
     * Adds an element, blocking while the bag is full.
     */
    public void add(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size] = item;
            size++;
            // Both producers and consumers wait on the same monitor, so notifyAll is required.
            lock.notifyAll();
        }
    }

    /**
     * Attempts to add an element without blocking.
     *
     * @return true if the element was added, false if the bag was full
     */
    public boolean tryAdd(T item) {
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
     * Removes and returns an arbitrary element, blocking while the bag is empty.
     */
    public T remove() throws InterruptedException {
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
     * Attempts to remove an arbitrary element without blocking.
     *
     * @return an element, or null if the bag was empty (null is never stored)
     */
    public T tryRemove() {
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
     * Returns true if at least one occurrence of the element (per equals) is present.
     */
    public boolean contains(T item) {
        if (item == null) {
            return false;
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns the number of occurrences of the element (per equals) in the bag.
     */
    public int count(T item) {
        if (item == null) {
            return 0;
        }
        synchronized (lock) {
            int n = 0;
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * Returns the current total number of elements (counting multiplicity).
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns the fixed capacity of this bag.
     */
    public int capacity() {
        // 'elements' is final and set before publication via the constructor.
        return elements.length;
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

    /**
     * Removes all elements from the bag, waking any threads blocked on add.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }
}
