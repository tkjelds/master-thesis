@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentList() {
        this(10);
    }

    public ConcurrentList(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public void add(E element) {
        synchronized (lock) {
            ensureCapacity(size + 1);
            elements[size++] = element;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            checkPositionIndex(index);
            ensureCapacity(size + 1);

            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
            }
            elements[index] = element;
            size++;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return elementAt(index);
        }
    }

    public E set(int index, E element) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = elementAt(index);
            elements[index] = element;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            E removed = elementAt(index);

            for (int i = index; i < size - 1; i++) {
                elements[i] = elements[i + 1];
            }
            elements[--size] = null;
            return removed;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            int index = indexOfElement(element);
            if (index < 0) {
                return false;
            }

            for (int i = index; i < size - 1; i++) {
                elements[i] = elements[i + 1];
            }
            elements[--size] = null;
            return true;
        }
    }

    public boolean contains(Object element) {
        synchronized (lock) {
            return indexOfElement(element) >= 0;
        }
    }

    public int indexOf(Object element) {
        synchronized (lock) {
            return indexOfElement(element);
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
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    private void ensureCapacity(int minimumCapacity) {
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List capacity exceeded");
        }
        if (minimumCapacity <= elements.length) {
            return;
        }

        int newCapacity = elements.length == 0 ? 1 : elements.length;
        while (newCapacity < minimumCapacity) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = minimumCapacity;
                break;
            }
            newCapacity *= 2;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Size: " + size
            );
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Size: " + size
            );
        }
    }

    private int indexOfElement(Object element) {
        for (int i = 0; i < size; i++) {
            Object current = elements[i];
            if (element == null ? current == null : element.equals(current)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
