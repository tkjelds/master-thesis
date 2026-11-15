@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<T> {
    @interface ThreadSafe {}


    /*
     * Concurrency policy:
     *  - The bag is a bounded multiset (duplicates allowed) with a fixed capacity.
     *  - add(T) blocks while the bag is full and resumes when space becomes available.
     *  - remove() blocks while the bag is empty and resumes when an element becomes available.
     *  - tryAdd(T) and tryRemove() never block; they return a failure indication immediately.
     *  - All state is guarded by a single private monitor. Every state change is followed by
     *    notifyAll(), so both blocked producers and blocked consumers re-check their guards.
     *  - Every wait is guarded by a while loop that re-tests the condition after waking.
     *  - null elements are rejected, so tryRemove() can use null to signal "empty".
     */

    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    /** Adds an element, blocking while the bag is full. */
    public void add(T item) throws InterruptedException {
        checkNotNull(item);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size++] = item;
            lock.notifyAll();
        }
    }

    /** Adds an element if space is available. Returns false immediately if the bag is full. */
    public boolean tryAdd(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[size++] = item;
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns an arbitrary element, blocking while the bag is empty. */
    public T remove() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T item = removeLastLocked();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns an arbitrary element, or null if the bag is empty. Never blocks. */
    public T tryRemove() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T item = removeLastLocked();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes one occurrence of the given element if present. Never blocks. */
    public boolean removeOccurrence(T item) {
        checkNotNull(item);
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    size--;
                    elements[i] = elements[size];
                    elements[size] = null;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns the number of occurrences of the given element. */
    public int count(T item) {
        checkNotNull(item);
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

    /** Returns true if at least one occurrence of the element is present. */
    public boolean contains(T item) {
        return count(item) > 0;
    }

    /** Returns the current number of elements (including duplicates). */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the bag currently contains no elements. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Returns the fixed capacity of the bag. */
    public int capacity() {
        return elements.length;
    }

    /** Removes all elements and wakes any blocked producers. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // Must be called while holding lock and with size > 0.
    @SuppressWarnings("unchecked")
    private T removeLastLocked() {
        size--;
        Object item = elements[size];
        elements[size] = null;
        return (T) item;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
