/**
 * A thread-safe, unbounded, doubly linked deque.
 *
 * All operations are non-blocking with respect to deque contents: removals and
 * peeks return null when empty. Null elements are permitted, so a null result
 * can also represent a stored null element.
 *
 * Each operation is atomic. Separate calls do not form an atomic transaction.
 */
@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Monitor {
    }

    private static final class Node<T> {
        T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Monitor monitor = new Monitor();

    // All mutable state is accessed only while holding monitor.
    private Node<E> first;
    private Node<E> last;
    private long count;

    public LinkedDeque() {
    }

    public void addFirst(E value) {
        synchronized (monitor) {
            Node<E> node = new Node<E>(value);
            node.next = first;

            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }

            first = node;
            count++;
        }
    }

    public void addLast(E value) {
        synchronized (monitor) {
            Node<E> node = new Node<E>(value);
            node.previous = last;

            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }

            last = node;
            count++;
        }
    }

    public E removeFirst() {
        synchronized (monitor) {
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

            E value = removed.value;
            removed.value = null;
            removed.next = null;
            count--;
            return value;
        }
    }

    public E removeLast() {
        synchronized (monitor) {
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

            E value = removed.value;
            removed.value = null;
            removed.previous = null;
            count--;
            return value;
        }
    }

    public E pollFirst() {
        return removeFirst();
    }

    public E pollLast() {
        return removeLast();
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

    public long size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return first == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            first = null;
            last = null;
            count = 0;
        }
    }
}
