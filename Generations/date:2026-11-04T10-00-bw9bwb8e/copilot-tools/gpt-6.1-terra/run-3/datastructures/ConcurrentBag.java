/**
 * A thread-safe, unbounded bag. Elements must be non-null.
 * Removal returns any one element, or null if the bag is empty.
 * Operations do not wait for elements; allocation may fail if memory is exhausted.
 */
@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[16];
    private int size;

    public void add(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            if (size == elements.length) {
                grow();
            }
            elements[size++] = element;
        }
    }

    /**
     * Removes and returns an element, or returns null if the bag is empty.
     */
    @SuppressWarnings("unchecked")
    public E remove() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            int index = --size;
            E element = (E) elements[index];
            elements[index] = null;
            return element;
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

    // Called only while holding lock.
    private void grow() {
        int oldCapacity = elements.length;
        if (oldCapacity == 2147483647) {
            throw new OutOfMemoryError("Bag capacity exhausted");
        }

        int newCapacity = oldCapacity <= 2147483647 / 2
                ? oldCapacity * 2
                : 2147483647;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
