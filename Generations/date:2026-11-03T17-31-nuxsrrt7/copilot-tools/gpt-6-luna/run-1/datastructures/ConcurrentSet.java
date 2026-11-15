@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentSet() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public boolean add(E value) {
        synchronized (lock) {
            if (indexOf(value) >= 0) {
                return false;
            }
            ensureCapacity(size + 1);
            elements[size++] = value;
            return true;
        }
    }

    public boolean contains(E value) {
        synchronized (lock) {
            return indexOf(value) >= 0;
        }
    }

    public boolean remove(E value) {
        synchronized (lock) {
            int index = indexOf(value);
            if (index < 0) {
                return false;
            }

            int elementsToMove = size - index - 1;
            if (elementsToMove > 0) {
                System.arraycopy(elements, index + 1, elements, index, elementsToMove);
            }
            elements[--size] = null;
            return true;
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

    private int indexOf(Object value) {
        for (int i = 0; i < size; i++) {
            Object element = elements[i];
            if (value == null ? element == null : value.equals(element)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= elements.length) {
            return;
        }

        int newCapacity = elements.length == 0 ? 1 : elements.length;
        while (newCapacity < requiredCapacity) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = Integer.MAX_VALUE;
            } else {
                newCapacity *= 2;
            }
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
