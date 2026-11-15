@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentBag() {
        this(10);
    }

    public ConcurrentBag(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public void add(E element) {
        synchronized (lock) {
            if (size == elements.length) {
                grow();
            }
            elements[size++] = element;
        }
    }

    public boolean remove(E element) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (same(elements[i], element)) {
                    int remaining = size - i - 1;
                    if (remaining > 0) {
                        System.arraycopy(elements, i + 1, elements, i, remaining);
                    }
                    elements[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(E element) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (same(elements[i], element)) {
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

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            System.arraycopy(elements, 0, result, 0, size);
            return result;
        }
    }

    private boolean same(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private void grow() {
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Bag is too large");
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity == 0 ? 1 : oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity < 0 || newCapacity <= size) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
