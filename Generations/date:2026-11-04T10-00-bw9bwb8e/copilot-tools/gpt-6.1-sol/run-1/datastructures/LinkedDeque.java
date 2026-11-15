/**
 * A thread-safe, doubly linked deque.
 *
 * Null elements are not permitted. Operations never wait for space or elements:
 * offer methods return false when full; add methods throw IllegalStateException.
 * Poll and peek methods return null when empty; remove and get methods throw
 * EmptyDequeException. All state is protected by a single private monitor.
 */
@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    public static final class EmptyDequeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EmptyDequeException() {
            super("Deque is empty");
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public LinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean offerFirst(E item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }

            Node<E> node = new Node<E>(item);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            size++;
            return true;
        }
    }

    public boolean offerLast(E item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }

            Node<E> node = new Node<E>(item);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            size++;
            return true;
        }
    }

    public void addFirst(E item) {
        if (!offerFirst(item)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public void addLast(E item) {
        if (!offerLast(item)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            if (first == null) {
                return null;
            }

            Node<E> removed = first;
            E item = removed.item;
            first = removed.next;

            if (first == null) {
                last = null;
            } else {
                first.previous = null;
            }

            removed.item = null;
            removed.next = null;
            size--;
            return item;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (last == null) {
                return null;
            }

            Node<E> removed = last;
            E item = removed.item;
            last = removed.previous;

            if (last == null) {
                first = null;
            } else {
                last.next = null;
            }

            removed.item = null;
            removed.previous = null;
            size--;
            return item;
        }
    }

    public E removeFirst() {
        E item = pollFirst();
        if (item == null) {
            throw new EmptyDequeException();
        }
        return item;
    }

    public E removeLast() {
        E item = pollLast();
        if (item == null) {
            throw new EmptyDequeException();
        }
        return item;
    }

    public E peekFirst() {
        synchronized (monitor) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return last == null ? null : last.item;
        }
    }

    public E getFirst() {
        E item = peekFirst();
        if (item == null) {
            throw new EmptyDequeException();
        }
        return item;
    }

    public E getLast() {
        E item = peekLast();
        if (item == null) {
            throw new EmptyDequeException();
        }
        return item;
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

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (monitor) {
            first = null;
            last = null;
            size = 0;
        }
    }
}
