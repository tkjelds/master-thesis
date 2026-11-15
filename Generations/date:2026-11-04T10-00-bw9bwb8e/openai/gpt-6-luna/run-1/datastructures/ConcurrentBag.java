@ConcurrentBag.ThreadSafe
public class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private int size;

    /**
     * Adds one occurrence of the value. Null values are allowed.
     */
    public void add(E value) {
        synchronized (lock) {
            ensureCapacity(size + 1);
            elements[size++] = value;
        }
    }

    /**
     * Removes one occurrence equal to the given value.
     *
     * @return true if an occurrence was removed; false if none was present
     */
    public boolean remove(Object value) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                Object element = elements[i];
                boolean matches = value == null
                        ? element == null
                        : value.equals(element);

                if (matches) {
                    int last = size - 1;
                    elements[i] = elements[last];
                    elements[last] = null;
                    size--;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                Object element = elements[i];
                if (value == null ? element == null : value.equals(element)) {
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

    /**
     * Returns a shallow snapshot of the bag's contents.
     */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] snapshot = new Object[size];
            for (int i = 0; i < size; i++) {
                snapshot[i] = elements[i];
            }
            return snapshot;
        }
    }

    private void ensureCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity < required || newCapacity < 0) {
            newCapacity = required;
        }

        Object[] replacement = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }
}
