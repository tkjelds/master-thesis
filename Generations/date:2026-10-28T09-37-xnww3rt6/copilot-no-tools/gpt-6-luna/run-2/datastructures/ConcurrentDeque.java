@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private final Object monitor = new Object();
    private Object[] elements;
    private int head;
    private int size;

    public ConcurrentDeque() {
        this(16);
    }

    public ConcurrentDeque(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        elements = new Object[initialCapacity];
    }

    /**
     * Adds an element at the front. Null elements are not permitted.
     * This unbounded deque accepts the element immediately unless allocation fails.
     */
    public boolean offerFirst(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (monitor) {
            ensureCapacity();
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = element;
            size++;
            return true;
        }
    }

    /**
     * Adds an element at the back. Null elements are not permitted.
     * This unbounded deque accepts the element immediately unless allocation fails.
     */
    public boolean offerLast(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (monitor) {
            ensureCapacity();
            int tail = indexAtOffset(size);
            elements[tail] = element;
            size++;
            return true;
        }
    }

    /**
     * Removes and returns the front element, or null if the deque is empty.
     */
    @SuppressWarnings("unchecked")
    public E pollFirst() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E element = (E) elements[head];
            elements[head] = null;
            size--;
            head = size == 0 ? 0 : nextIndex(head);
            return element;
        }
    }

    /**
     * Removes and returns the back element, or null if the deque is empty.
     */
    @SuppressWarnings("unchecked")
    public E pollLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            int tail = indexAtOffset(size - 1);
            E element = (E) elements[tail];
            elements[tail] = null;
            size--;
            if (size == 0) {
                head = 0;
            }
            return element;
        }
    }

    /**
     * Returns the front element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peekFirst() {
        synchronized (monitor) {
            return size == 0 ? null : (E) elements[head];
        }
    }

    /**
     * Returns the back element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peekLast() {
        synchronized (monitor) {
            return size == 0 ? null : (E) elements[indexAtOffset(size - 1)];
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < size; i++) {
                elements[indexAtOffset(i)] = null;
            }
            size = 0;
            head = 0;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }
        int oldCapacity = elements.length;
        if (oldCapacity >= MAX_ARRAY_SIZE) {
            throw new OutOfMemoryError("Deque capacity exceeded");
        }
        int newCapacity = oldCapacity <= MAX_ARRAY_SIZE / 2
                ? oldCapacity * 2
                : MAX_ARRAY_SIZE;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[indexAtOffset(i)];
        }
        elements = expanded;
        head = 0;
    }

    private int indexAtOffset(int offset) {
        int distanceToEnd = elements.length - head;
        return offset >= distanceToEnd ? offset - distanceToEnd : head + offset;
    }

    private int nextIndex(int index) {
        return index == elements.length - 1 ? 0 : index + 1;
    }
}
