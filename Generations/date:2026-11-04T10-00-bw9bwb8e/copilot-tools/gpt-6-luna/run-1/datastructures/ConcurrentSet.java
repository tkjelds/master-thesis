@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentSet() {
        this(8);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public boolean add(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(value)) {
                    return false;
                }
            }

            ensureCapacity(size + 1);
            elements[size++] = value;
            return true;
        }
    }

    public boolean remove(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(value)) {
                    int elementsToMove = size - i - 1;
                    if (elementsToMove > 0) {
                        System.arraycopy(elements, i + 1, elements, i, elementsToMove);
                    }
                    elements[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (elements[i].equals(value)) {
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
            Object[] copy = new Object[size];
            System.arraycopy(elements, 0, copy, 0, size);
            return copy;
        }
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 1;
        } else if (oldCapacity <= Integer.MAX_VALUE / 2) {
            newCapacity = oldCapacity * 2;
        } else {
            newCapacity = Integer.MAX_VALUE;
        }

        if (newCapacity < requiredCapacity) {
            newCapacity = requiredCapacity;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
