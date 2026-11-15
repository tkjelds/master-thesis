@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
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

    public void addFirst(E element) {
        requireNonNull(element);
        synchronized (lock) {
            ensureCapacity();
            head = (head - 1 + elements.length) % elements.length;
            elements[head] = element;
            size++;
        }
    }

    public void addLast(E element) {
        requireNonNull(element);
        synchronized (lock) {
            ensureCapacity();
            elements[(head + size) % elements.length] = element;
            size++;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = elementAt(head);
            elements[head] = null;
            head = (head + 1) % elements.length;
            size--;
            if (size == 0) {
                head = 0;
            }
            return element;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int index = (head + size - 1) % elements.length;
            E element = elementAt(index);
            elements[index] = null;
            size--;
            if (size == 0) {
                head = 0;
            }
            return element;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return size == 0 ? null : elementAt((head + size - 1) % elements.length);
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
                elements[(head + i) % elements.length] = null;
            }
            head = 0;
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }
        if (elements.length == Integer.MAX_VALUE) {
            throw new IllegalStateException("Deque capacity exceeded");
        }

        int newCapacity = elements.length <= (Integer.MAX_VALUE - 1) / 2
                ? elements.length * 2
                : Integer.MAX_VALUE;
        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[(head + i) % elements.length];
        }
        elements = expanded;
        head = 0;
    }

    private E elementAt(int index) {
        @SuppressWarnings("unchecked")
        E element = (E) elements[index];
        return element;
    }

    private static void requireNonNull(Object element) {
        if (element == null) {
            throw new NullPointerException("Deque does not allow null elements");
        }
    }
}
