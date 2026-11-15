@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}


    // Policy:
    // - add(E) blocks while the list is full (bounded capacity). tryAdd(E) never blocks.
    // - removeFirst() blocks while the list is empty.
    // - get, set, and removeAt are non-blocking and throw IndexOutOfBoundsException for invalid indices.
    // - Interruption while blocked is reported by throwing InterruptedException.
    // - All state is guarded by a single private monitor; every read and write occurs inside
    //   a synchronized block on that monitor, which provides mutual exclusion and visibility.
    // - Waiters re-check their condition in while loops, and every state change calls notifyAll,
    //   because producers and consumers share the same monitor.

    private final Object lock = new Object();
    private final int capacity;
    private Object[] elements;
    private int size;

    public ConcurrentList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.elements = new Object[Math.min(capacity, 16)];
        this.size = 0;
    }

    /**
     * Appends an element, blocking while the list is full.
     */
    public void add(E element) throws InterruptedException {
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            ensureCapacityLocked(size + 1);
            elements[size] = element;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Appends an element if space is available; never blocks.
     *
     * @return true if the element was added, false if the list was full
     */
    public boolean tryAdd(E element) {
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            ensureCapacityLocked(size + 1);
            elements[size] = element;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the first element, blocking while the list is empty.
     */
    @SuppressWarnings("unchecked")
    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = (E) elements[0];
            System.arraycopy(elements, 1, elements, 0, size - 1);
            size--;
            elements[size] = null;
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Returns the element at the given index.
     *
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (lock) {
            checkIndexLocked(index);
            return (E) elements[index];
        }
    }

    /**
     * Replaces the element at the given index and returns the previous element.
     *
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        synchronized (lock) {
            checkIndexLocked(index);
            E previous = (E) elements[index];
            elements[index] = element;
            return previous;
        }
    }

    /**
     * Removes and returns the element at the given index.
     *
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    @SuppressWarnings("unchecked")
    public E removeAt(int index) {
        synchronized (lock) {
            checkIndexLocked(index);
            E removed = (E) elements[index];
            int moved = size - index - 1;
            if (moved > 0) {
                System.arraycopy(elements, index + 1, elements, index, moved);
            }
            size--;
            elements[size] = null;
            lock.notifyAll();
            return removed;
        }
    }

    /**
     * Returns the number of elements currently in the list.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the list currently contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the maximum number of elements the list can hold.
     */
    public int capacity() {
        return capacity;
    }

    private void checkIndexLocked(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void ensureCapacityLocked(int required) {
        if (required <= elements.length) {
            return;
        }
        long newLength = Math.max((long) required, (long) elements.length * 2L);
        newLength = Math.min(newLength, (long) capacity);
        Object[] grown = new Object[(int) newLength];
        System.arraycopy(elements, 0, grown, 0, size);
        elements = grown;
    }
}
