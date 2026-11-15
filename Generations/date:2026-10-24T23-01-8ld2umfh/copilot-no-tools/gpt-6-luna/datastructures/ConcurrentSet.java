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
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public boolean add(E element) {
        synchronized (lock) {
            if (indexOf(element) >= 0) {
                return false;
            }

            ensureCapacity();
            elements[size++] = element;
            return true;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            int index = indexOf(element);
            if (index < 0) {
                return false;
            }

            int remaining = size - index - 1;
            for (int i = 0; i < remaining; i++) {
                elements[index + i] = elements[index + i + 1];
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

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            Object current = elements[i];
            if (element == current || (element != null && element.equals(current))) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        if (oldCapacity == Integer.MAX_VALUE) {
            throw new IllegalStateException("Set capacity limit reached");
        }

        int newCapacity = oldCapacity == 0
                ? 1
                : oldCapacity <= Integer.MAX_VALUE / 2
                        ? oldCapacity * 2
                        : Integer.MAX_VALUE;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
