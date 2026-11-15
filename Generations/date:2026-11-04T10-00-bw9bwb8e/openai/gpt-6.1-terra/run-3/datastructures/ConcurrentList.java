/**
 * A thread-safe, dynamically sized list. Null elements are permitted.
 *
 * Each public operation is atomic. The list does not wait for elements or
 * capacity: invalid indices throw IndexOutOfBoundsException, and allocation
 * failures propagate as OutOfMemoryError.
 */
@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[8];
    private int size;

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

    // Called only while holding lock.
    private void ensureRoom() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }
        if (size < elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1) + 1;
        if (newCapacity < 0 || newCapacity <= oldCapacity) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] replacement = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }

    // Called only while holding lock.
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
        }
    }

    // Called only while holding lock.
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
        }
    }
}
