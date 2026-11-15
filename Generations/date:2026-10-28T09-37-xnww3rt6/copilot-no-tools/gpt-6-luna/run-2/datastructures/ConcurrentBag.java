@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private int size;

    /**
     * Adds one occurrence of the specified element.
     * Null elements are not permitted.
     */
    public boolean add(E element) {
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }

        synchronized (lock) {
            if (size == elements.length) {
                grow();
            }
            elements[size++] = element;
            return true;
        }
    }

    /**
     * Removes one occurrence equal to the specified element, if present.
     */
    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    elements[i] = elements[size - 1];
                    elements[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns and removes an arbitrary element, or null if the bag is empty.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            int index = --size;
            Object element = elements[index];
            elements[index] = null;
            return (E) element;
        }
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    return true;
                }
            }
            return false;
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

    private void grow() {
        int oldCapacity = elements.length;
        if (oldCapacity == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Bag capacity exceeded");
        }

        int newCapacity = oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity <= oldCapacity || newCapacity < 0) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] grown = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            grown[i] = elements[i];
        }
        elements = grown;
    }
}
