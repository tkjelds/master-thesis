@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentSet() {
        this(16);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be nonnegative");
        }
        elements = new Object[initialCapacity];
    }

    public boolean add(E value) {
        requireNonNull(value);
        synchronized (lock) {
            if (indexOf(value) >= 0) {
                return false;
            }
            ensureCapacity();
            elements[size++] = value;
            return true;
        }
    }

    public boolean remove(Object value) {
        requireNonNull(value);
        synchronized (lock) {
            int index = indexOf(value);
            if (index < 0) {
                return false;
            }
            int remaining = size - index - 1;
            if (remaining > 0) {
                System.arraycopy(elements, index + 1, elements, index, remaining);
            }
            elements[--size] = null;
            return true;
        }
    }

    public boolean contains(Object value) {
        requireNonNull(value);
        synchronized (lock) {
            return indexOf(value) >= 0;
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

    private int indexOf(Object value) {
        for (int i = 0; i < size; i++) {
            if (value.equals(elements[i])) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }
        int newCapacity = elements.length == 0 ? 1 : elements.length << 1;
        if (newCapacity < 0) {
            newCapacity = Integer.MAX_VALUE;
        }
        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }
}
