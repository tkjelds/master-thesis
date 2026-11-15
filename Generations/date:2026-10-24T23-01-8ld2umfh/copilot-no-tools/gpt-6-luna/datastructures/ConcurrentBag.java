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
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public void add(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (lock) {
            ensureCapacity();
            elements[size++] = element;
        }
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
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

    public Object[] toArray() {
        synchronized (lock) {
            return java.util.Arrays.copyOf(elements, size);
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity == 0 ? 1 : oldCapacity * 2;
        if (newCapacity < 0) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
