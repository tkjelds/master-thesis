@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[0];
    private int size;

    public ConcurrentList() {
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

    @SuppressWarnings("unchecked")
    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return (E) elements[index];
        }
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = (E) elements[index];
            elements[index] = element;
            return previous;
        }
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = (E) elements[index];
            int moved = size - index - 1;
            if (moved > 0) {
                System.arraycopy(elements, index + 1, elements, index, moved);
            }
            elements[--size] = null;
            return previous;
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

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity < 0) {
            throw new OutOfMemoryError("Required array size too large");
        }
        if (minimumCapacity <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity == 0 ? 10 : oldCapacity + (oldCapacity >> 1);
        if (newCapacity < minimumCapacity) {
            newCapacity = minimumCapacity;
        }
        if (newCapacity < 0) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
