@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<T> {
    @interface ThreadSafe {}


    private static final int UNBOUNDED = Integer.MAX_VALUE;
    private static final int DEFAULT_INITIAL_ARRAY_SIZE = 16;

    // Single intrinsic monitor guarding all mutable state.
    private final Object lock = new Object();

    // Maximum number of elements the bag may hold.
    private final int capacity;

    // Backing storage. Guarded by 'lock'. Elements occupy indices [0, size).
    private Object[] items;
    private int size;

    /**
     * Creates an unbounded bag.
     * add() never blocks; remove() blocks while the bag is empty.
     */
    public ConcurrentBag() {
        this(UNBOUNDED);
    }

    /**
     * Creates a bag with the given maximum capacity.
     * add() blocks while the bag is full; remove() blocks while the bag is empty.
     *
     * @param capacity maximum number of elements, must be greater than zero
     */
    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        int initial = Math.min(capacity, DEFAULT_INITIAL_ARRAY_SIZE);
        this.items = new Object[initial];
        this.size = 0;
    }

    /**
     * Adds an element to the bag. If the bag is full, the calling thread
     * waits until space becomes available or it is interrupted.
     *
     * @param item non-null element to add
     * @throws NullPointerException if item is null
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void add(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insertLocked(item);
            lock.notifyAll();
        }
    }

    /**
     * Attempts to add an element without blocking.
     *
     * @param item non-null element to add
     * @return true if the element was added, false if the bag was full
     * @throws NullPointerException if item is null
     */
    public boolean tryAdd(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insertLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns an element from the bag. If the bag is empty, the
     * calling thread waits until an element becomes available or it is interrupted.
     *
     * @return a removed element (never null)
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public T remove() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T result = removeLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Attempts to remove an element without blocking.
     *
     * @return a removed element, or null if the bag was empty
     */
    public T tryRemove() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T result = removeLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Removes one occurrence of the given element, if present.
     *
     * @param item element to remove (equality via equals)
     * @return true if an occurrence was removed
     */
    public boolean removeItem(T item) {
        if (item == null) {
            return false;
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (equalsNullSafe(items[i], item)) {
                    // Move last element into the hole to keep storage compact.
                    items[i] = items[size - 1];
                    items[size - 1] = null;
                    size--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns the number of occurrences of the given element in the bag.
     *
     * @param item element to count (equality via equals)
     * @return occurrence count, or 0 if item is null
     */
    public int count(T item) {
        if (item == null) {
            return 0;
        }
        synchronized (lock) {
            int n = 0;
            for (int i = 0; i < size; i++) {
                if (equalsNullSafe(items[i], item)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * @param item element to test (equality via equals)
     * @return true if at least one occurrence of item is in the bag
     */
    public boolean contains(T item) {
        return count(item) > 0;
    }

    /**
     * @return the current number of elements in the bag
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * @return true if the bag currently holds no elements
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * @return the maximum capacity, or Integer.MAX_VALUE if unbounded
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Removes all elements from the bag and wakes any blocked producers.
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                items[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    // ----- Private helpers; callers must hold 'lock' -----

    private void insertLocked(T item) {
        if (size == items.length) {
            grow();
        }
        items[size] = item;
        size++;
    }

    private T removeLocked() {
        size--;
        @SuppressWarnings("unchecked")
        T result = (T) items[size];
        items[size] = null;
        return result;
    }

    private void grow() {
        long doubled = (long) items.length * 2L;
        int newLength = (int) Math.min(doubled, (long) capacity);
        if (newLength <= items.length) {
            newLength = items.length + 1;
        }
        Object[] bigger = new Object[newLength];
        for (int i = 0; i < size; i++) {
            bigger[i] = items[i];
        }
        items = bigger;
    }

    private static boolean equalsNullSafe(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
