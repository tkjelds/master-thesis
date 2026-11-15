@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements = new Object[16];
    private int size;

    // Null elements are not permitted; remove returns null when the bag is empty.
    public void add(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            if (size == elements.length) {
                if (size == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Bag is too large");
                }

                int newCapacity = elements.length <= Integer.MAX_VALUE / 2
                        ? elements.length * 2
                        : Integer.MAX_VALUE;
                Object[] expanded = new Object[newCapacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = elements[i];
                }
                elements = expanded;
            }

            elements[size++] = element;
        }
    }

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
}
