/**
 * A thread-safe, dynamically sized list. Null elements are permitted.
 *
 * Operations do not wait for elements or capacity, although they may wait
 * to acquire the internal monitor. Invalid indexes throw
 * IndexOutOfBoundsException, including accesses to an empty list.
 * Growth is limited by available memory and Java array limits.
 */
@ConcurrentList.ThreadSafe
@SuppressWarnings("unchecked")
public class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentList() {
        synchronized (lock) {
            elements = new Object[8];
            size = 0;
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

    public boolean add(E element) {
        synchronized (lock) {
            ensureCapacityForOne();
            elements[size] = element;
            size++;
            return true;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            checkPositionIndex(index);
            ensureCapacityForOne();
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
            return (E) elements[index];
        }
    }

    public E set(int index, E element) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = (E) elements[index];
            elements[index] = element;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = (E) elements[index];
            for (int i = index; i < size - 1; i++) {
                elements[i] = elements[i + 1];
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

    // The caller must hold lock.
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
        }
    }

    // The caller must hold lock.
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
        }
    }

    // The caller must hold lock.
    private void ensureCapacityForOne() {
        if (size < elements.length) {
            return;
        }
        if (size == 2147483647) {
            throw new OutOfMemoryError("List is too large");
        }

        long proposed = (long) elements.length + (elements.length >> 1) + 1;
        int newCapacity = proposed > 2147483647L
                ? 2147483647
                : (int) proposed;

        Object[] replacement = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }
}
