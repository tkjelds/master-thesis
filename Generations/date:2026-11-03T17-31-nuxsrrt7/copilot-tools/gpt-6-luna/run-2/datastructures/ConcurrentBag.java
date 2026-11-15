@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int size;

    /**
     * Creates a bag with fixed capacity. Adding to a full bag and removing an
     * absent item return false immediately; neither operation blocks.
     */
    public ConcurrentBag(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        elements = new Object[capacity];
    }

    /** Adds an item, returning false if the bag is full. Null items are allowed. */
    public boolean add(E item) {
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            elements[size++] = item;
            return true;
        }
    }

    /** Removes one matching item, returning false if no match is present. */
    public boolean remove(Object item) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (same(item, elements[i])) {
                    int last = --size;
                    elements[i] = elements[last];
                    elements[last] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object item) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (same(item, elements[i])) {
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

    public int capacity() {
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

    /** Removes all items from the bag. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;
        }
    }

    /** Returns a shallow snapshot of the items currently in the bag. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] snapshot = new Object[size];
            for (int i = 0; i < size; i++) {
                snapshot[i] = elements[i];
            }
            return snapshot;
        }
    }

    private static boolean same(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
