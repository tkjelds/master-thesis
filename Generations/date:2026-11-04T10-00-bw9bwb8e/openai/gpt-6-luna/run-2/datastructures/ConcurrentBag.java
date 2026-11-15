@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentBag() {
        this(16);
    }

    public ConcurrentBag(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity cannot be negative");
        }
        elements = new Object[initialCapacity];
    }

    public void add(E element) {
        synchronized (lock) {
            ensureCapacity(size + 1);
            elements[size++] = element;
        }
    }

    /**
     * Removes one occurrence of the given element, if present.
     *
     * @return true if an occurrence was removed
     */
    public boolean remove(E element) {
        synchronized (lock) {
            int index = indexOf(element);
            if (index < 0) {
                return false;
            }

            int moved = size - index - 1;
            if (moved > 0) {
                System.arraycopy(elements, index + 1, elements, index, moved);
            }
            elements[--size] = null;
            return true;
        }
    }

    public boolean contains(E element) {
        synchronized (lock) {
            return indexOf(element) >= 0;
        }
    }

    public int count(E element) {
        synchronized (lock) {
            int occurrences = 0;
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(element)) {
                    occurrences++;
                }
            }
            return occurrences;
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
     * Returns a snapshot of the bag's elements. Changes to the returned array
     * do not affect this bag.
     */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] snapshot = new Object[size];
            System.arraycopy(elements, 0, snapshot, 0, size);
            return snapshot;
        }
    }

    private int indexOf(E element) {
        for (int i = 0; i < size; i++) {
            if (elements[i].equals(element)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= elements.length) {
            return;
        }

        int newCapacity = elements.length == 0 ? 1 : elements.length * 2;
        if (newCapacity < 0 || newCapacity < requiredCapacity) {
            newCapacity = requiredCapacity;
        }

        Object[] replacement = new Object[newCapacity];
        System.arraycopy(elements, 0, replacement, 0, size);
        elements = replacement;
    }
}
