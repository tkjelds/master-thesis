/**
 * A thread-safe, nonblocking linked deque.
 *
 * Null elements are not permitted. The maximum size is Integer.MAX_VALUE.
 * Offers return false when full; adds throw IllegalStateException.
 * Polls and peeks return null when empty; removes and gets throw
 * IllegalStateException.
 */
@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public boolean offerFirst(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
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
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
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

    public void addFirst(E value) {
        if (!offerFirst(value)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public void addLast(E value) {
        if (!offerLast(value)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (first == null) {
                return null;
            }

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
    }

    public E pollLast() {
        synchronized (lock) {
            if (last == null) {
                return null;
            }

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

    public E removeFirst() {
        E value = pollFirst();
        if (value == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return value;
    }

    public E removeLast() {
        E value = pollLast();
        if (value == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return value;
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

    public E getFirst() {
        E value = peekFirst();
        if (value == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return value;
    }

    public E getLast() {
        E value = peekLast();
        if (value == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return value;
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
            first = null;
            last = null;
            size = 0;
        }
    }
}
