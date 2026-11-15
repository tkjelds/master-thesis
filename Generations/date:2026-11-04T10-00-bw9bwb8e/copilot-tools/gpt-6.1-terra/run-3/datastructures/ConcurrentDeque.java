/**
 * A bounded, thread-safe deque. Operations never wait: offers return false
 * when full, and polls and peeks return null when empty. Null elements are
 * prohibited.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> previous;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean offerFirst(E value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            Node<E> node = new Node<E>(value);
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

    public boolean offerLast(E value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            Node<E> node = new Node<E>(value);
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

    public E pollFirst() {
        synchronized (lock) {
            if (first == null) {
                return null;
            }
            Node<E> node = first;
            first = node.next;
            if (first == null) {
                last = null;
            } else {
                first.previous = null;
            }
            size--;
            return node.value;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (last == null) {
                return null;
            }
            Node<E> node = last;
            last = node.previous;
            if (last == null) {
                first = null;
            } else {
                last.next = null;
            }
            size--;
            return node.value;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.value;
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

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            size = 0;
        }
    }
}
