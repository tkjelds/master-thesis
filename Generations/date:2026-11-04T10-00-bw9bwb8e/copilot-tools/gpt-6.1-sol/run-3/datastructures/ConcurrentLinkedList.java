/**
 * A thread-safe, unbounded, doubly linked list.
 *
 * Null elements are permitted. Poll and peek operations never block and return
 * null when the list is empty; this is indistinguishable from a null element.
 * All operations are serialized using a private intrinsic monitor.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private T value;
        private Node<T> previous;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private Node<E> first;
    private Node<E> last;
    private long size;

    public ConcurrentLinkedList() {
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
            size++;
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
            size++;
        }
    }

    public void add(E value) {
        addLast(value);
    }

    public E pollFirst() {
        synchronized (monitor) {
            if (first == null) {
                return null;
            }

            Node<E> removed = first;
            E value = removed.value;
            first = removed.next;

            if (first == null) {
                last = null;
            } else {
                first.previous = null;
            }

            removed.value = null;
            removed.next = null;
            size--;
            return value;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (last == null) {
                return null;
            }

            Node<E> removed = last;
            E value = removed.value;
            last = removed.previous;

            if (last == null) {
                first = null;
            } else {
                last.next = null;
            }

            removed.value = null;
            removed.previous = null;
            size--;
            return value;
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

    public long size() {
        synchronized (monitor) {
            return size;
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
            size = 0;
        }
    }
}
