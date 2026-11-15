@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private int size;

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

            int lastIndex = --size;
            elements[index] = elements[lastIndex];
            elements[lastIndex] = null;
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

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            Object current = elements[i];
            if (element == null ? current == null : element.equals(current)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= elements.length) {
            return;
        }

        int newCapacity = elements.length * 2;
        if (newCapacity < requiredCapacity || newCapacity < 0) {
            newCapacity = requiredCapacity;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
