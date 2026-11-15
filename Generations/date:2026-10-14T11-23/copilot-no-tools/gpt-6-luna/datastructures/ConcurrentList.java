public final class ConcurrentList<E> {
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

    public boolean contains(E element) {
        synchronized (lock) {
            return indexOfElement(element) >= 0;
        }
    }

    public int indexOf(E element) {
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
            System.arraycopy(elements, 0, copy, 0, size);
            return copy;
        }
    }

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity < 0) {
            throw new OutOfMemoryError("List capacity exceeded");
        }
        if (minimumCapacity <= elements.length) {
            return;
        }

        long doubled = (long) elements.length * 2;
        long candidate = doubled < 10 ? 10 : doubled;
        if (candidate < minimumCapacity) {
            candidate = minimumCapacity;
        }
        if (candidate > Integer.MAX_VALUE) {
            candidate = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[(int) candidate];
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

    private int indexOfElement(E element) {
        for (int i = 0; i < size; i++) {
            Object current = elements[i];
            if (element == null ? current == null : element.equals(current)) {
                return i;
            }
        }
        return -1;
    }
}
