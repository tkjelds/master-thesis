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

    public boolean add(E element) {
        synchronized (lock) {
            ensureCapacity(size + 1);
            elements[size++] = element;
            return true;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            checkPositionIndex(index);
            ensureCapacity(size + 1);
            int moved = size - index;
            if (moved > 0) {
                System.arraycopy(elements, index, elements, index + 1, moved);
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
            int moved = size - index - 1;
            if (moved > 0) {
                System.arraycopy(elements, index + 1, elements, index, moved);
            }
            elements[--size] = null;
            return removed;
        }
    }

    public boolean remove(Object value) {
        synchronized (lock) {
            int index = indexOfLocked(value);
            if (index < 0) {
                return false;
            }
            int moved = size - index - 1;
            if (moved > 0) {
                System.arraycopy(elements, index + 1, elements, index, moved);
            }
            elements[--size] = null;
            return true;
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            return indexOfLocked(value) >= 0;
        }
    }

    public int indexOf(Object value) {
        synchronized (lock) {
            return indexOfLocked(value);
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

    private int indexOfLocked(Object value) {
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
        if (minimumCapacity < 0) {
            throw new OutOfMemoryError("Required array size is too large");
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity < minimumCapacity || newCapacity < 0) {
            newCapacity = minimumCapacity;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
