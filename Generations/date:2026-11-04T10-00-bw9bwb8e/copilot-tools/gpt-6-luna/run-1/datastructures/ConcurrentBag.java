@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int count;

    public ConcurrentBag() {
        this(16);
    }

    public ConcurrentBag(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public boolean add(E element) {
        synchronized (lock) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException("Bag is too large");
            }
            ensureCapacity(count + 1);
            elements[count++] = element;
            return true;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (equal(elements[i], element)) {
                    int remaining = count - i - 1;
                    for (int j = 0; j < remaining; j++) {
                        elements[i + j] = elements[i + j + 1];
                    }
                    elements[--count] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(Object element) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (equal(elements[i], element)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                elements[i] = null;
            }
            count = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[count];
            for (int i = 0; i < count; i++) {
                result[i] = elements[i];
            }
            return result;
        }
    }

    private void ensureCapacity(int required) {
        if (required <= elements.length) {
            return;
        }

        int newCapacity = elements.length == 0 ? 1 : elements.length;
        while (newCapacity < required) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = Integer.MAX_VALUE;
                break;
            }
            newCapacity *= 2;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < count; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }

    private boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
