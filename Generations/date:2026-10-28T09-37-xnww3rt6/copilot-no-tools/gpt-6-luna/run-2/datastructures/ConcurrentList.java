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
            throw new IllegalArgumentException("Initial capacity cannot be negative");
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
            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
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
            E removed = (E) elements[index];
            for (int i = index; i < size - 1; i++) {
                elements[i] = elements[i + 1];
            }
            elements[--size] = null;
            return removed;
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
            for (int i = 0; i < size; i++) {
                result[i] = elements[i];
            }
            return result;
        }
    }

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity <= elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity;
        if (oldCapacity == 0) {
            newCapacity = 1;
        } else if (oldCapacity > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = oldCapacity * 2;
        }

        if (newCapacity < minimumCapacity) {
            newCapacity = minimumCapacity;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
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
}
