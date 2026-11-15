@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Object[] elements;
    private byte[] states;
    private int size;
    private int usedSlots;

    public ConcurrentSet() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentSet(int initialCapacity) {
        if (initialCapacity <= 0 || initialCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("initialCapacity must be between 1 and " + MAX_CAPACITY);
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        elements = new Object[capacity];
        states = new byte[capacity];
    }

    public boolean add(E element) {
        synchronized (lock) {
            if (findIndex(element) >= 0) {
                return false;
            }

            ensureSpaceForInsert();
            int index = findInsertIndex(element);
            if (index < 0) {
                throw new IllegalStateException("Set has no available slots");
            }

            if (states[index] == 0) {
                usedSlots++;
            }
            elements[index] = element;
            states[index] = 1;
            size++;
            return true;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            int index = findIndex(element);
            if (index < 0) {
                return false;
            }

            elements[index] = null;
            states[index] = 2;
            size--;
            return true;
        }
    }

    public boolean contains(Object element) {
        synchronized (lock) {
            return findIndex(element) >= 0;
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
            elements = new Object[elements.length];
            states = new byte[states.length];
            size = 0;
            usedSlots = 0;
        }
    }

    private int findIndex(Object element) {
        int mask = elements.length - 1;
        int index = hash(element) & mask;

        for (int probes = 0; probes < elements.length; probes++) {
            if (states[index] == 0) {
                return -1;
            }
            if (states[index] == 1 && elementsEqual(elements[index], element)) {
                return index;
            }
            index = (index + 1) & mask;
        }

        return -1;
    }

    private int findInsertIndex(Object element) {
        int mask = elements.length - 1;
        int index = hash(element) & mask;
        int firstDeleted = -1;

        for (int probes = 0; probes < elements.length; probes++) {
            if (states[index] == 0) {
                return firstDeleted >= 0 ? firstDeleted : index;
            }
            if (states[index] == 2 && firstDeleted < 0) {
                firstDeleted = index;
            }
            index = (index + 1) & mask;
        }

        return firstDeleted;
    }

    private void ensureSpaceForInsert() {
        int capacity = elements.length;
        int threshold = capacity - capacity / 4;

        if (usedSlots + 1 <= threshold) {
            return;
        }

        if (capacity < MAX_CAPACITY) {
            int newCapacity = size + 1 > threshold ? capacity << 1 : capacity;
            rehash(newCapacity);
            return;
        }

        if (usedSlots != size) {
            rehash(capacity);
        } else if (size == capacity) {
            throw new IllegalStateException("Set has reached its maximum capacity");
        }
    }

    private void rehash(int newCapacity) {
        Object[] oldElements = elements;
        byte[] oldStates = states;
        Object[] newElements = new Object[newCapacity];
        byte[] newStates = new byte[newCapacity];
        int mask = newCapacity - 1;

        for (int i = 0; i < oldElements.length; i++) {
            if (oldStates[i] == 1) {
                Object element = oldElements[i];
                int index = hash(element) & mask;

                while (newStates[index] == 1) {
                    index = (index + 1) & mask;
                }

                newElements[index] = element;
                newStates[index] = 1;
            }
        }

        elements = newElements;
        states = newStates;
        usedSlots = size;
    }

    private static int hash(Object element) {
        int value = element == null ? 0 : element.hashCode();
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return value;
    }

    private static boolean elementsEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
