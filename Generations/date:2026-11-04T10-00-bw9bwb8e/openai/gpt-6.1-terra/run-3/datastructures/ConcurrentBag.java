/**
 * An unbounded, thread-safe bag. Duplicate elements are permitted; null is not.
 * Removal selects an arbitrary element. No ordering or fairness is guaranteed.
 *
 * add never waits for capacity, though allocation can fail.
 * remove and peek return null when empty. take waits until an element is
 * available and is interruptible.
 */
@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[16];
    private int size;

    public boolean add(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            if (size == elements.length) {
                grow();
            }
            elements[size++] = element;
            lock.notifyAll();
            return true;
        }
    }

    public E remove() {
        synchronized (lock) {
            return size == 0 ? null : removeLast();
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeLast();
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[size - 1];
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
    @SuppressWarnings("unchecked")
    private E removeLast() {
        int index = --size;
        E element = (E) elements[index];
        elements[index] = null;
        return element;
    }

    // Called only while holding lock; leaves the bag unchanged if allocation fails.
    private void grow() {
        int oldCapacity = elements.length;
        if (oldCapacity == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Bag capacity exceeded");
        }

        int newCapacity = oldCapacity > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE
                : oldCapacity * 2;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
