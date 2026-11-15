@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentSet() {
        this(8);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    /**
     * Adds an element, returning false if an equal element is already present.
     * Null elements are supported.
     */
    public boolean add(E element) {
        synchronized (lock) {
            if (indexOf(element) >= 0) {
                return false;
            }

            if (size == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("Set is too large");
            }
            ensureCapacity(size + 1);
            elements[size++] = element;
            return true;
        }
    }

    /**
     * Removes an equal element, returning false if no such element is present.
     */
    public boolean remove(Object element) {
        synchronized (lock) {
            int index = indexOf(element);
            if (index < 0) {
                return false;
            }

            int elementsToMove = size - index - 1;
            if (elementsToMove > 0) {
                System.arraycopy(elements, index + 1, elements, index, elementsToMove);
            }
            elements[--size] = null;
            return true;
        }
    }

    public boolean contains(Object element) {
        synchronized (lock) {
            return indexOf(element) >= 0;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
        }
    }

    /**
     * Returns a snapshot of the elements. The returned array is independent
     * of this set and may be modified by the caller.
     */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] snapshot = new Object[size];
            System.arraycopy(elements, 0, snapshot, 0, size);
            return snapshot;
        }
    }

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            Object existing = elements[i];
            if (element == existing ||
                    (element != null && element.equals(existing))) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 1;
        } else if (oldCapacity > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = oldCapacity * 2;
        }

        if (newCapacity < minimumCapacity) {
            newCapacity = minimumCapacity;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
