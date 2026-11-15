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
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public boolean add(E element) {
        synchronized (lock) {
            if (indexOf(element) >= 0) {
                return false;
            }
            ensureCapacity(size + 1);
            elements[size++] = element;
            return true;
        }
    }

    public boolean remove(E element) {
        synchronized (lock) {
            int index = indexOf(element);
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

    public boolean contains(E element) {
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

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            System.arraycopy(elements, 0, result, 0, size);
            return result;
        }
    }

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            if (valuesEqual(elements[i], element)) {
                return i;
            }
        }
        return -1;
    }

    private boolean valuesEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity == 0 ? 8 : oldCapacity;
        while (newCapacity < requiredCapacity) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = Integer.MAX_VALUE;
            } else {
                newCapacity *= 2;
            }
            if (newCapacity < requiredCapacity && newCapacity == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("Required array size too large");
            }
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
