@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private int size;

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

    public boolean remove(Object value) {
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

    public boolean contains(Object value) {
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
            Object element = elements[i];
            if (value == null ? element == null : value.equals(element)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity <= elements.length) {
            return;
        }

        int newCapacity = elements.length <= Integer.MAX_VALUE / 2
                ? elements.length * 2
                : Integer.MAX_VALUE;
        if (newCapacity < minimumCapacity) {
            newCapacity = minimumCapacity;
        }

        Object[] newElements = new Object[newCapacity];
        System.arraycopy(elements, 0, newElements, 0, size);
        elements = newElements;
    }
}
