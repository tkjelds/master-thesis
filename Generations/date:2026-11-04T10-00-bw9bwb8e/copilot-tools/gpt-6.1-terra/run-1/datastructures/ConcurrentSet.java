/**
 * A thread-safe, dynamically growing set.
 *
 * Operations may wait to acquire the internal monitor, but never wait for
 * capacity or for an element to become available. Memory exhaustion may cause
 * an insertion to fail with OutOfMemoryError.
 *
 * Elements may be null. Element equality must remain stable while elements are
 * stored, and equals must not call operations on this set.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private long size;

    public boolean add(E value) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (equal(value, node.value)) {
                    return false;
                }
            }

            head = new Node<E>(value, head);
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> node = head;

            while (node != null) {
                if (equal(value, node.value)) {
                    if (previous == null) {
                        head = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return true;
                }
                previous = node;
                node = node.next;
            }

            return false;
        }
    }

    public boolean contains(E value) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (equal(value, node.value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public long size() {
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
            size = 0;
        }
    }

    private static boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
