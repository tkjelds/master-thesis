@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

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

    public boolean offerFirst(E value) {
        requireNonNull(value);
        synchronized (this) {
            ensureCapacity();
            head = head == 0 ? elements.length - 1 : head - 1;
            elements[head] = value;
            size++;
            return true;
        }
    }

    public boolean offerLast(E value) {
        requireNonNull(value);
        synchronized (this) {
            ensureCapacity();
            elements[physicalIndex(size)] = value;
            size++;
            return true;
        }
    }

    public void addFirst(E value) {
        offerFirst(value);
    }

    public void addLast(E value) {
        offerLast(value);
    }

    public E pollFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }

            E value = elementAt(head);
            elements[head] = null;
            head++;
            if (head == elements.length) {
                head = 0;
            }
            size--;
            if (size == 0) {
                head = 0;
            }
            return value;
        }
    }

    public E pollLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }

            int index = physicalIndex(size - 1);
            E value = elementAt(index);
            elements[index] = null;
            size--;
            if (size == 0) {
                head = 0;
            }
            return value;
        }
    }

    public E removeFirst() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("deque is empty");
            }

            E value = elementAt(head);
            elements[head] = null;
            head++;
            if (head == elements.length) {
                head = 0;
            }
            size--;
            if (size == 0) {
                head = 0;
            }
            return value;
        }
    }

    public E removeLast() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("deque is empty");
            }

            int index = physicalIndex(size - 1);
            E value = elementAt(index);
            elements[index] = null;
            size--;
            if (size == 0) {
                head = 0;
            }
            return value;
        }
    }

    public E peekFirst() {
        synchronized (this) {
            return size == 0 ? null : elementAt(head);
        }
    }

    public E peekLast() {
        synchronized (this) {
            return size == 0 ? null : elementAt(physicalIndex(size - 1));
        }
    }

    public E getFirst() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("deque is empty");
            }
            return elementAt(head);
        }
    }

    public E getLast() {
        synchronized (this) {
            if (size == 0) {
                throw new IllegalStateException("deque is empty");
            }
            return elementAt(physicalIndex(size - 1));
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
                elements[physicalIndex(i)] = null;
            }
            head = 0;
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size < elements.length) {
            return;
        }
        if (elements.length >= (Integer.MAX_VALUE / 2)) {
            throw new IllegalStateException("deque capacity limit reached");
        }

        Object[] expanded = new Object[elements.length * 2];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[physicalIndex(i)];
        }
        elements = expanded;
        head = 0;
    }

    private int physicalIndex(int offset) {
        int distanceToEnd = elements.length - head;
        return offset >= distanceToEnd ? offset - distanceToEnd : head + offset;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }

    private void requireNonNull(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }
    }
}
