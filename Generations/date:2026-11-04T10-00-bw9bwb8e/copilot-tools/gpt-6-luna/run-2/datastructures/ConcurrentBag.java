@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentBag() {
        this(8);
    }

    public ConcurrentBag(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be nonnegative");
        }
        elements = new Object[initialCapacity];
    }

    /** Adds a non-null item. */
    public boolean add(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            ensureCapacity();
            elements[size++] = item;
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns an arbitrary item, or null if the bag is empty. */
    public E poll() {
        synchronized (lock) {
            return size == 0 ? null : removeAt(size - 1);
        }
    }

    /** Waits until an item is available, then removes and returns an arbitrary item. */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeAt(size - 1);
        }
    }

    /** Removes one item equal to the argument, if present. */
    public boolean remove(Object item) {
        if (item == null) {
            return false;
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
                    removeAt(i);
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object item) {
        if (item == null) {
            return false;
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (item.equals(elements[i])) {
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

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 1;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else if (oldCapacity < Integer.MAX_VALUE) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            throw new OutOfMemoryError("Bag capacity exceeded");
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    @SuppressWarnings("unchecked")
    private E removeAt(int index) {
        E removed = (E) elements[index];
        int lastIndex = --size;
        elements[index] = elements[lastIndex];
        elements[lastIndex] = null;
        return removed;
    }
}
