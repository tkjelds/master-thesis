/**
 * A thread-safe, dynamically sized set. Null is permitted.
 * Operations do not wait for elements or capacity, though they may wait
 * to acquire the set's monitor.
 *
 * Elements must obey the usual equals contract and must not change in a
 * way that affects equality while stored in the set.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[16];
    private int size;

    public boolean add(E element) {
        synchronized (lock) {
            if (indexOf(element) >= 0) {
                return false;
            }

            if (size == elements.length) {
                grow();
            }

            elements[size] = element;
            size++;
            return true;
        }
    }

    public boolean contains(Object element) {
        synchronized (lock) {
            return indexOf(element) >= 0;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            int index = indexOf(element);
            if (index < 0) {
                return false;
            }

            int last = size - 1;
            elements[index] = elements[last];
            elements[last] = null;
            size = last;
            return true;
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

    private int indexOf(Object element) {
        for (int i = 0; i < size; i++) {
            if (element == null ? elements[i] == null : element.equals(elements[i])) {
                return i;
            }
        }
        return -1;
    }

    private void grow() {
        if (elements.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Set is too large");
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
