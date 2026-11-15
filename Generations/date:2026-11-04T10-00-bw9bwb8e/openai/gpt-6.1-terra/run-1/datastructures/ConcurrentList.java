/**
 * A thread-safe, dynamically sized list.
 *
 * Operations do not wait for elements or capacity. Invalid indices throw
 * IndexOutOfBoundsException; allocation failure may throw OutOfMemoryError.
 * Null elements are permitted.
 */
@ConcurrentList.ThreadSafe
public class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentList() {
        elements = new Object[8];
    }

    public ConcurrentList(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Negative capacity");
        }
        elements = new Object[initialCapacity];
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

    public boolean add(E element) {
        synchronized (lock) {
            ensureRoom();
            elements[size] = element;
            size++;
            return true;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            checkPositionIndex(index);
            ensureRoom();
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

    /**
     * Returns a snapshot of the list in encounter order.
     */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] snapshot = new Object[size];
            for (int i = 0; i < size; i++) {
                snapshot[i] = elements[i];
            }
            return snapshot;
        }
    }

    // The following helpers are called only while holding lock.
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void ensureRoom() {
        if (size < elements.length) {
            return;
        }
        if (size == 2147483647) {
            throw new OutOfMemoryError("List is too large");
        }

        long newCapacity = elements.length == 0
                ? 8L
                : (long) elements.length + (elements.length >> 1) + 1L;
        if (newCapacity > 2147483647L) {
            newCapacity = 2147483647L;
        }

        Object[] replacement = new Object[(int) newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }
}
