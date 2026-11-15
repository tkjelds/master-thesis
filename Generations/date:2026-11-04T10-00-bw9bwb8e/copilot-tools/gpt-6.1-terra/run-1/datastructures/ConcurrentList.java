/**
 * An unbounded, thread-safe, indexed list. Null elements are permitted.
 * Operations do not wait for elements or capacity; invalid indices throw
 * IndexOutOfBoundsException, and allocation failures propagate normally.
 */
@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private int size;

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

    public void add(E element) {
        synchronized (lock) {
            ensureCapacityForOneMore();
            elements[size] = element;
            size++;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }
            ensureCapacityForOneMore();
            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
            }
            elements[index] = element;
            size++;
        }
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = (E) elements[index];
            for (int i = index; i < size - 1; i++) {
                elements[i] = elements[i + 1];
            }
            size--;
            elements[size] = null;
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

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void ensureCapacityForOneMore() {
        if (size < elements.length) {
            return;
        }
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        int newCapacity = elements.length > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE
                : elements.length * 2;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
