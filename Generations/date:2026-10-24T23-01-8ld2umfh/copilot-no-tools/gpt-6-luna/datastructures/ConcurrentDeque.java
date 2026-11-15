@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentDeque() {
        this(16);
    }

    public ConcurrentDeque(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        elements = new Object[initialCapacity];
    }

    public void addFirst(E element) {
        requireNonNull(element);
        synchronized (this) {
            ensureCapacity();
            head = decrement(head, elements.length);
            elements[head] = element;
            size++;
        }
    }

    public void addLast(E element) {
        requireNonNull(element);
        synchronized (this) {
            ensureCapacity();
            elements[tail] = element;
            tail = increment(tail, elements.length);
            size++;
        }
    }

    public boolean offerFirst(E element) {
        addFirst(element);
        return true;
    }

    public boolean offerLast(E element) {
        addLast(element);
        return true;
    }

    public E pollFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E element = elementAt(head);
            elements[head] = null;
            head = increment(head, elements.length);
            size--;
            if (size == 0) {
                head = 0;
                tail = 0;
            }
            return element;
        }
    }

    public E pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            tail = decrement(tail, elements.length);
            E element = elementAt(tail);
            elements[tail] = null;
            size--;
            if (size == 0) {
                head = 0;
                tail = 0;
            }
            return element;
        }
    }

    public E removeFirst() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            E element = elementAt(head);
            elements[head] = null;
            head = increment(head, elements.length);
            size--;
            if (size == 0) {
                head = 0;
                tail = 0;
            }
            return element;
        }
    }

    public E removeLast() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            tail = decrement(tail, elements.length);
            E element = elementAt(tail);
            elements[tail] = null;
            size--;
            if (size == 0) {
                head = 0;
                tail = 0;
            }
            return element;
        }
    }

    public E peekFirst() {
        synchronized (this) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (this) {
            return size == 0 ? null : elementAt(decrement(tail, elements.length));
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                elements[(head + i) % elements.length] = null;
            }
            head = 0;
            tail = 0;
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }

        int oldCapacity = elements.length;
        if (oldCapacity == Integer.MAX_VALUE) {
            throw new IllegalStateException("Deque capacity limit reached");
        }
        int newCapacity = oldCapacity <= Integer.MAX_VALUE / 2
                ? oldCapacity * 2
                : Integer.MAX_VALUE;

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[(head + i) % oldCapacity];
        }
        elements = expanded;
        head = 0;
        tail = size;
    }

    private E elementAt(int index) {
        @SuppressWarnings("unchecked")
        E element = (E) elements[index];
        return element;
    }

    private static int increment(int index, int capacity) {
        return index + 1 == capacity ? 0 : index + 1;
    }

    private static int decrement(int index, int capacity) {
        return index == 0 ? capacity - 1 : index - 1;
    }

    private static void requireNonNull(Object element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }
}
