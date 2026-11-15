@ConcurrentLinkedDeque.ThreadSafe
public final class ConcurrentLinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    /**
     * Adds an element at the front. Null elements are not permitted.
     * The deque is unbounded, so this operation does not wait for capacity.
     */
    public void addFirst(E element) {
        requireElement(element);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(element);
            node.next = head;
            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }
            head = node;
            size++;
        }
    }

    /** Adds an element at the back. Null elements are not permitted. */
    public void addLast(E element) {
        requireElement(element);
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(element);
            node.previous = tail;
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    /** Adds an element at the front. Always returns true unless an exception occurs. */
    public boolean offerFirst(E element) {
        addFirst(element);
        return true;
    }

    /** Adds an element at the back. Always returns true unless an exception occurs. */
    public boolean offerLast(E element) {
        addLast(element);
        return true;
    }

    /**
     * Removes and returns the front element, or returns null if the deque is empty.
     * Empty-deque operations are nonblocking.
     */
    public E pollFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            } else {
                head.previous = null;
            }
            node.next = null;
            size--;
            return node.item;
        }
    }

    /** Removes and returns the back element, or returns null if the deque is empty. */
    public E pollLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }
            Node<E> node = tail;
            tail = node.previous;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            node.previous = null;
            size--;
            return node.item;
        }
    }

    /** Removes and returns the front element, or throws if the deque is empty. */
    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("Deque is empty");
            }
            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            } else {
                head.previous = null;
            }
            node.next = null;
            size--;
            return node.item;
        }
    }

    /** Removes and returns the back element, or throws if the deque is empty. */
    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("Deque is empty");
            }
            Node<E> node = tail;
            tail = node.previous;
            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }
            node.previous = null;
            size--;
            return node.item;
        }
    }

    /** Returns the front element, or null if the deque is empty. */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    /** Returns the back element, or null if the deque is empty. */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    /** Returns the front element, or throws if the deque is empty. */
    public E getFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return head.item;
        }
    }

    /** Returns the back element, or throws if the deque is empty. */
    public E getLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return tail.item;
        }
    }

    /** Adds an element at the front. */
    public void push(E element) {
        addFirst(element);
    }

    /** Removes and returns the front element, or throws if the deque is empty. */
    public E pop() {
        return removeFirst();
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
            head = null;
            tail = null;
            size = 0;
        }
    }

    private static void requireElement(Object element) {
        if (element == null) {
            throw new IllegalArgumentException("Null elements are not permitted");
        }
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Deque is too large");
        }
    }
}
