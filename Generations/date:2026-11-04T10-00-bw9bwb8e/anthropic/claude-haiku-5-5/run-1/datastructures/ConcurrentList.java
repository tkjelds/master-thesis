@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    /**
     * Creates a bounded, thread-safe list with the given fixed capacity.
     *
     * Policy:
     *  - add(e) and removeFirst() block while the list is full / empty respectively.
     *  - tryAdd(e) and tryRemoveFirst() never block; they return a failure indicator.
     *  - get, set, remove(int) throw IndexOutOfBoundsException for invalid indices.
     *  - null elements are rejected with NullPointerException.
     *  - Blocking operations throw InterruptedException if the calling thread is interrupted,
     *    leaving the list state unchanged.
     */
    public ConcurrentList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.elements = new Object[capacity];
        this.size = 0;
    }

    /** Appends an element, blocking while the list is full. */
    public void add(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size++] = e;
            lock.notifyAll();
        }
    }

    /** Appends an element if space is available. Never blocks. */
    public boolean tryAdd(E e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[size++] = e;
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the first element, blocking while the list is empty. */
    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = removeFirstUnchecked();
            lock.notifyAll();
            return result;
        }
    }

    /** Removes and returns the first element, or null if the list is empty. Never blocks. */
    public E tryRemoveFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = removeFirstUnchecked();
            lock.notifyAll();
            return result;
        }
    }

    /** Returns the element at the given index. */
    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return castElement(elements[index]);
        }
    }

    /** Replaces the element at the given index and returns the previous element. */
    public E set(int index, E e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            checkIndex(index);
            E old = castElement(elements[index]);
            elements[index] = e;
            return old;
        }
    }

    /** Removes the element at the given index, shifting subsequent elements left. */
    public E remove(int index) {
        synchronized (lock) {
            checkIndex(index);
            E old = castElement(elements[index]);
            int moved = size - index - 1;
            if (moved > 0) {
                System.arraycopy(elements, index + 1, elements, index, moved);
            }
            elements[--size] = null;
            lock.notifyAll();
            return old;
        }
    }

    /** Returns the index of the first occurrence of o, or -1 if absent. */
    public int indexOf(Object o) {
        if (o == null) {
            return -1;
        }
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** Removes all elements. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
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

    public int capacity() {
        return elements.length;
    }

    // Caller must hold lock and ensure size > 0.
    private E removeFirstUnchecked() {
        E result = castElement(elements[0]);
        System.arraycopy(elements, 1, elements, 0, size - 1);
        elements[--size] = null;
        return result;
    }

    // Caller must hold lock.
    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
    }

    @SuppressWarnings("unchecked")
    private E castElement(Object o) {
        return (E) o;
    }
}
