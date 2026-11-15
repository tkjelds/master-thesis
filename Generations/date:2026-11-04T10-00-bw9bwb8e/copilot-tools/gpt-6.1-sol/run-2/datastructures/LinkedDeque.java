/**
 * A thread-safe, doubly linked deque.
 *
 * Null elements are not permitted. Poll and peek operations return null when
 * empty; take operations wait interruptibly until an element is available.
 * Insertions never wait and throw IllegalStateException if the size would
 * exceed the maximum representable int value.
 *
 * All mutable state is protected by a private monitor.
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

    private final Object monitor = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public LinkedDeque() {
    }

    public void addFirst(E item) {
        synchronized (monitor) {
            checkInsertion(item);

            Node<E> node = new Node<E>(item);
            node.next = first;

            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }

            first = node;
            size++;
            monitor.notifyAll();
        }
    }

    public void addLast(E item) {
        synchronized (monitor) {
            checkInsertion(item);

            Node<E> node = new Node<E>(item);
            node.previous = last;

            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }

            last = node;
            size++;
            monitor.notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            return first == null ? null : unlink(first);
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            return last == null ? null : unlink(last);
        }
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

    /**
     * Waits while empty. Interruption during the wait leaves the deque unchanged.
     */
    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (first == null) {
                monitor.wait();
            }
            return unlink(first);
        }
    }

    /**
     * Waits while empty. Interruption during the wait leaves the deque unchanged.
     */
    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (last == null) {
                monitor.wait();
            }
            return unlink(last);
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

    // Called only while holding monitor.
    private void checkInsertion(E item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        if (size == 2147483647) {
            throw new IllegalStateException("Maximum deque size reached");
        }
    }

    // Called only while holding monitor, with a node belonging to this deque.
    private E unlink(Node<E> node) {
        Node<E> previous = node.previous;
        Node<E> next = node.next;

        if (previous == null) {
            first = next;
        } else {
            previous.next = next;
        }

        if (next == null) {
            last = previous;
        } else {
            next.previous = previous;
        }

        E item = node.item;
        node.item = null;
        node.previous = null;
        node.next = null;
        size--;
        return item;
    }
}
