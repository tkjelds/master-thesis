/**
 * An unbounded, thread-safe set backed by a linked list.
 *
 * Null elements are supported. Element equality is determined by equals;
 * elements should have stable equality while stored in the set.
 *
 * Operations may wait to acquire the set's monitor, but never wait for
 * capacity or for an element to become available. Add, remove, and contains
 * take linear time; size takes constant time.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E element;
        Node<E> next;

        Node(E element, Node<E> next) {
            this.element = element;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private long size;
    private Object revision = new Object();

    /**
     * Adds an element if it is not already present.
     *
     * @return true if the set changed
     * @throws IllegalStateException if the size cannot be represented
     */
    public boolean add(E element) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (matches(element, node.element)) {
                    return false;
                }
            }

            if (size == Long.MAX_VALUE) {
                throw new IllegalStateException("Set size limit reached");
            }

            Node<E> newHead = new Node<E>(element, head);
            Object newRevision = new Object();
            head = newHead;
            size++;
            revision = newRevision;
            return true;
        }
    }

    /**
     * Removes an equal element if one is present.
     *
     * @return true if the set changed
     */
    public boolean remove(Object element) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (matches(element, current.element)) {
                    Object newRevision = new Object();
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    revision = newRevision;
                    return true;
                }
                previous = current;
                current = current.next;
            }
            return false;
        }
    }

    /** Returns whether the set contains an equal element. */
    public boolean contains(Object element) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (matches(element, node.element)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns the number of elements currently in the set. */
    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns whether the set is empty. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Removes all elements from the set. */
    public void clear() {
        synchronized (lock) {
            if (head != null) {
                Object newRevision = new Object();
                head = null;
                size = 0;
                revision = newRevision;
            }
        }
    }

    /*
     * Called with lock held. Detects structural changes made reentrantly by
     * an element's equals method before the caller uses its traversal state.
     */
    private boolean matches(Object sought, Object stored) {
        if (sought == stored) {
            return true;
        }
        if (sought == null) {
            return false;
        }

        Object before = revision;
        boolean equal = sought.equals(stored);
        if (revision != before) {
            throw new IllegalStateException(
                "Set modified reentrantly during element comparison"
            );
        }
        return equal;
    }
}
