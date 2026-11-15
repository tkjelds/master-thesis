@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int initialCapacity;
    private Object[] elements;
    private int size;

    public ConcurrentBag() {
        this(10);
    }

    public ConcurrentBag(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }
        this.initialCapacity = initialCapacity;
        this.elements = new Object[initialCapacity];
    }

    public void add(T element) {
        if (element == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }

        synchronized (lock) {
            if (size == elements.length) {
                ensureCapacity(size + 1);
            }
            elements[size++] = element;
        }
    }

    public boolean remove(T element) {
        if (element == null) {
            return false;
        }

        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                if (element.equals(elements[i])) {
                    int elementsToMove = size - i - 1;
                    if (elementsToMove > 0) {
                        System.arraycopy(elements, i + 1, elements, i, elementsToMove);
                    }
                    elements[--size] = null;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean contains(T element) {
        if (element == null) {
            return false;
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

    public int count(T element) {
        if (element == null) {
            return 0;
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

    public Object[] toArray() {
        synchronized (lock) {
            Object[] snapshot = new Object[size];
            System.arraycopy(elements, 0, snapshot, 0, size);
            return snapshot;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < size; i++) {
                elements[i] = null;
            }
            size = 0;

            if (elements.length > initialCapacity) {
                elements = new Object[initialCapacity];
            }
        }
    }

    private void ensureCapacity(int minimumCapacity) {
        if (minimumCapacity <= elements.length) {
            return;
        }

        if (minimumCapacity < 0) {
            throw new OutOfMemoryError("Bag capacity exceeded");
        }

        long grownCapacity = (long) elements.length + elements.length / 2L + 1L;
        if (grownCapacity < minimumCapacity) {
            grownCapacity = minimumCapacity;
        }
        if (grownCapacity > Integer.MAX_VALUE - 8L) {
            grownCapacity = Integer.MAX_VALUE - 8L;
        }
        if (grownCapacity < minimumCapacity) {
            throw new OutOfMemoryError("Bag capacity exceeded");
        }

        Object[] expanded = new Object[(int) grownCapacity];
        System.arraycopy(elements, 0, expanded, 0, size);
        elements = expanded;
    }
}
