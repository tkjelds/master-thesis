@ConcurrentBoundedSet.ThreadSafe
public class ConcurrentBoundedSet<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    /**
     * Creates a set with a fixed maximum capacity.
     *
     * @param capacity maximum number of distinct elements the set may hold; must be positive
     */
    public ConcurrentBoundedSet(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    /**
     * Adds an element to the set.
     *
     * Policy:
     * - Returns false immediately if the element is already present (set semantics).
     * - If the element is absent and the set is full, the calling thread blocks
     *   until another thread removes an element or the thread is interrupted.
     * - Null elements are rejected with NullPointerException.
     *
     * @return true if the element was added, false if it was already present
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean add(T element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (true) {
                if (indexOfLocked(element) >= 0) {
                    return false;
                }
                if (size < elements.length) {
                    elements[size] = element;
                    size++;
                    lock.notifyAll();
                    return true;
                }
                lock.wait();
            }
        }
    }

    /**
     * Removes an element from the set. This operation never blocks.
     *
     * @return true if the element was present and removed, false otherwise
     */
    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            int index = indexOfLocked(element);
            if (index < 0) {
                return false;
            }
            int last = size - 1;
            elements[index] = elements[last];
            elements[last] = null;
            size = last;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Tests whether the set contains the given element. This operation never blocks.
     */
    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            return indexOfLocked(element) >= 0;
        }
    }

    /**
     * Returns the number of elements currently in the set.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the set currently contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the maximum number of elements this set can hold.
     */
    public int capacity() {
        return elements.length;
    }

    /**
     * Must be called while holding {@code lock}.
     */
    private int indexOfLocked(Object element) {
        for (int i = 0; i < size; i++) {
            if (element.equals(elements[i])) {
                return i;
            }
        }
        return -1;
    }
}
