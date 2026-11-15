/**
 * A thread-safe, unbounded, non-blocking deque.
 *
 * Null elements are not permitted. Poll and peek operations return null when
 * empty; remove and get operations throw EmptyDequeException instead.
 * All operations are serialized by a private intrinsic monitor.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    public static final class EmptyDequeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EmptyDequeException() {
            super("Deque is empty");
        }
    }

    private final Object monitor = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public ConcurrentDeque() {
    }

    public void addFirst(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            checkSizeLimit();
            Node<E> node = new Node<E>(value);
            node.next = first;

            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }

            first = node;
            size++;
        }
    }

    public void addLast(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            checkSizeLimit();
            Node<E> node = new Node<E>(value);
            node.previous = last;

            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }

            last = node;
            size++;
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            return first == null ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            return last == null ? null : unlinkLast();
        }
    }

    public E removeFirst() {
        synchronized (monitor) {
            if (first == null) {
                throw new EmptyDequeException();
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new EmptyDequeException();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return last == null ? null : last.value;
        }
    }

    public E getFirst() {
        synchronized (monitor) {
            if (first == null) {
                throw new EmptyDequeException();
            }
            return first.value;
        }
    }

    public E getLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new EmptyDequeException();
            }
            return last.value;
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
            first = null;
            last = null;
            size = 0;
        }
    }

    // Private helpers are called only while holding monitor.
    private void checkSizeLimit() {
        if (size == 2147483647) {
            throw new IllegalStateException("Maximum deque size reached");
        }
    }

    private E unlinkFirst() {
        Node<E> removed = first;
        first = removed.next;

        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }

        removed.next = null;
        size--;
        return removed.value;
    }

    private E unlinkLast() {
        Node<E> removed = last;
        last = removed.previous;

        if (last == null) {
            first = null;
        } else {
            last.next = null;
        }

        removed.previous = null;
        size--;
        return removed.value;
    }
}
