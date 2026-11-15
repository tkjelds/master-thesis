@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[16];
    private int size;

    public boolean add(E element) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (same(elements[i], element)) {
                    return false;
                }
            }

            if (size == elements.length) {
                grow();
            }
            elements[size++] = element;
            return true;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (same(elements[i], element)) {
                    int remaining = size - i - 1;
                    for (int j = 0; j < remaining; j++) {
                        elements[i + j] = elements[i + j + 1];
                    }
                    elements[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object element) {
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

    private void grow() {
        if (elements.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Set capacity exceeded");
        }

        int newLength = elements.length > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE
                : elements.length * 2;
        Object[] expanded = new Object[newLength];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private static boolean same(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
