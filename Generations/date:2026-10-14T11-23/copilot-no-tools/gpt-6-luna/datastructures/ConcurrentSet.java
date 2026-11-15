public final class ConcurrentSet {
    private final int[] lock = new int[0];
    private int[] elements;
    private int size;

    public ConcurrentSet() {
        this(16);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        elements = new int[initialCapacity];
    }

    public boolean add(int value) {
        synchronized (lock) {
            if (indexOf(value) >= 0) {
                return false;
            }

            if (size == elements.length) {
                grow();
            }

            elements[size++] = value;
            return true;
        }
    }

    public boolean remove(int value) {
        synchronized (lock) {
            int index = indexOf(value);
            if (index < 0) {
                return false;
            }

            elements[index] = elements[size - 1];
            size--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (lock) {
            return indexOf(value) >= 0;
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
            size = 0;
        }
    }

    private int indexOf(int value) {
        for (int i = 0; i < size; i++) {
            if (elements[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private void grow() {
        int oldCapacity = elements.length;
        int newCapacity = oldCapacity << 1;

        if (newCapacity <= oldCapacity) {
            newCapacity = Integer.MAX_VALUE;
        }

        int[] expanded = new int[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
