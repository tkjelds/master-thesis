@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentBag() {
        this(8);
    }

    public ConcurrentBag(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        elements = new Object[initialCapacity];
    }

    public void add(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (lock) {
            ensureCapacity();
            elements[size++] = element;
        }
    }

    public boolean remove(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    int remaining = size - i - 1;
                    if (remaining > 0) {
                        System.arraycopy(elements, i + 1, elements, i, remaining);
                    }
                    elements[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    public int count(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }

        synchronized (lock) {
            int occurrences = 0;
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    occurrences++;
                }
            }
            return occurrences;
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
            System.arraycopy(elements, 0, copy, 0, size);
            return copy;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        if (elements.length == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Bag capacity exceeded");
        }

        int newCapacity = elements.length == 0 ? 1 : elements.length * 2;
        if (newCapacity < 0 || newCapacity > Integer.MAX_VALUE) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[newCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
