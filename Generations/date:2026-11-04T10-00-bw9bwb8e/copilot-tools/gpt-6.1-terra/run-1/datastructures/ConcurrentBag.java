@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[16];
    private int size;

    // The bag accepts null. Adding grows the backing array when necessary.
    public void add(E element) {
        synchronized (lock) {
            if (size == elements.length) {
                grow();
            }
            elements[size] = element;
            size++;
        }
    }

    // Removes an arbitrary element; throws immediately if the bag is empty.
    @SuppressWarnings("unchecked")
    public E remove() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Bag is empty");
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

    // Called only while holding lock.
    private void grow() {
        int oldCapacity = elements.length;
        if (oldCapacity == 2147483647) {
            throw new OutOfMemoryError("Bag capacity exhausted");
        }
        int newCapacity = oldCapacity > 1073741823
                ? 2147483647
                : oldCapacity * 2;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
