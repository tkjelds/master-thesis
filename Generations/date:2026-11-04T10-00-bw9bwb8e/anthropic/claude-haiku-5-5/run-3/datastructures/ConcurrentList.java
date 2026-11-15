@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}


    private static final int DEFAULT_INITIAL_CAPACITY = 8;

    /*
     * Concurrency policy:
     *  - All state (elements, size) is guarded by the single monitor 'lock'.
     *    Every access, including reads, happens inside a synchronized (lock) block,
     *    which provides mutual exclusion, visibility, and safe publication.
     *  - Null elements are rejected with NullPointerException.
     *  - Blocking operations:
     *      add(E) and add(int, E) block while the list is full (bounded lists only).
     *      take() blocks while the list is empty.
     *  - Non-blocking operations:
     *      tryAdd(E), pollFirst(), get, set, remove(int), indexOf, contains, size, etc.
     *      Index-based operations throw IndexOutOfBoundsException for invalid indices
     *      instead of waiting, because an index that is invalid now may be valid later
     *      and the caller must decide how to handle it.
     *  - Every state change that can unblock a waiter calls notifyAll().
     *  - Every wait() is guarded by a while loop that re-checks the condition.
     *  - InterruptedException from wait() is propagated to the caller; the interrupt
     *    status is preserved by the JVM semantics of wait() throwing it.
     */

    private final Object lock = new Object();
    private final int capacity;
    private Object[] elements;
    private int size;

    public ConcurrentList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentList(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.elements = new Object[Math.min(capacity, DEFAULT_INITIAL_CAPACITY)];
        this.size = 0;
    }

    // ---------------------------------------------------------------- blocking operations

    /** Appends an element, blocking while the bounded list is full. */
    public void add(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            insertLocked(size, e);
            lock.notifyAll();
        }
    }

    /** Inserts an element at the given index, blocking while the bounded list is full. */
    public void add(int index, E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            checkIndexForInsert(index);
            insertLocked(index, e);
            lock.notifyAll();
        }
    }

    /** Removes and returns the first element, blocking while the list is empty. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = removeAtLocked(0);
            lock.notifyAll();
            return result;
        }
    }

    // ---------------------------------------------------------------- non-blocking operations

    /** Appends an element if there is room. Returns false instead of blocking. */
    public boolean tryAdd(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            insertLocked(size, e);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the first element, or null if the list is empty. */
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = removeAtLocked(0);
            lock.notifyAll();
            return result;
        }
    }

    /** Returns the element at index. Throws IndexOutOfBoundsException if invalid. */
    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return (E) elements[index];
        }
    }

    /** Replaces the element at index and returns the previous element. */
    @SuppressWarnings("unchecked")
    public E set(int index, E e) {
        checkNotNull(e);
        synchronized (lock) {
            checkIndex(index);
            E old = (E) elements[index];
            elements[index] = e;
            return old;
        }
    }

    /** Removes the element at index and returns it. Throws IndexOutOfBoundsException if invalid. */
    public E remove(int index) {
        synchronized (lock) {
            checkIndex(index);
            E result = removeAtLocked(index);
            lock.notifyAll();
            return result;
        }
    }

    /** Removes the first occurrence of o (using equals). Returns true if removed. */
    public boolean removeElement(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            int idx = indexOfLocked(o);
            if (idx < 0) {
                return false;
            }
            removeAtLocked(idx);
            lock.notifyAll();
            return true;
        }
    }

    /** Returns the index of the first occurrence of o, or -1 if absent. */
    public int indexOf(Object o) {
        if (o == null) {
            return -1;
        }
        synchronized (lock) {
            return indexOfLocked(o);
        }
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
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

    public int capacity() {
        return capacity;
    }

    /** Removes all elements and wakes any blocked threads so they can re-check state. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot copy of the current contents. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(elements[i]);
            }
            sb.append(']');
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- private helpers (lock held)

    private void insertLocked(int index, E e) {
        ensureCapacityLocked(size + 1);
        for (int i = size; i > index; i--) {
            elements[i] = elements[i - 1];
        }
        elements[index] = e;
        size++;
    }

    @SuppressWarnings("unchecked")
    private E removeAtLocked(int index) {
        E result = (E) elements[index];
        for (int i = index; i < size - 1; i++) {
            elements[i] = elements[i + 1];
        }
        size--;
        elements[size] = null;
        return result;
    }

    private int indexOfLocked(Object o) {
        for (int i = 0; i < size; i++) {
            if (o.equals(elements[i])) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacityLocked(int minCapacity) {
        if (elements.length >= minCapacity) {
            return;
        }
        int newLength;
        if (elements.length > capacity / 2) {
            newLength = capacity;
        } else {
            newLength = elements.length * 2;
        }
        if (newLength < minCapacity) {
            newLength = minCapacity;
        }
        Object[] grown = new Object[newLength];
        for (int i = 0; i < size; i++) {
            grown[i] = elements[i];
        }
        elements = grown;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    private void checkIndexForInsert(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
