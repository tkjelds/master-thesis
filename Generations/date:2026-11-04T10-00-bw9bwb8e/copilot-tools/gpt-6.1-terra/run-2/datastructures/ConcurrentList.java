@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[0];
    private int size;

    /*
     * Operations do not wait for elements or capacity. Invalid indexes throw
     * IndexOutOfBoundsException; growth can fail with OutOfMemoryError.
     * Null elements are permitted.
     */

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

    public boolean add(E element) {
        synchronized (lock) {
            ensureRoomForOne();
            elements[size] = element;
            size++;
            return true;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            ensureRoomForOne();
            for (int i = size; i > index; i--) {
                elements[i] = elements[i - 1];
            }
            elements[index] = element;
            size++;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            E previous = elementAt(index);
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

    public Object[] toArray() {
        synchronized (lock) {
            Object[] copy = new Object[size];
            for (int i = 0; i < size; i++) {
                copy[i] = elements[i];
            }
            return copy;
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void ensureRoomForOne() {
        if (size < elements.length) {
            return;
        }
        if (size == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("List is too large");
        }

        long proposed = (long) elements.length + (elements.length >> 1) + 1;
        int newCapacity = proposed > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) proposed;
        Object[] replacement = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            replacement[i] = elements[i];
        }
        elements = replacement;
    }
}
