/**
 * A thread-safe bag that permits duplicates but not null elements.
 *
 * Removal returns an arbitrary element, or null if the bag is empty.
 * Operations do not wait for elements or space, although they may wait to
 * acquire the internal monitor. Adding at the maximum array capacity throws
 * IllegalStateException; allocation failures propagate normally.
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
            elements[size] = element;
            size++;
        }
    }

    @SuppressWarnings("unchecked")
    public E remove() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            int index = size - 1;
            E element = (E) elements[index];
            elements[index] = null;
            size = index;
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
            throw new IllegalStateException("Bag has reached maximum capacity");
        }

        long proposed = (long) oldCapacity + (oldCapacity >> 1) + 1;
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
