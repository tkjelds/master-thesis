/**
 * A thread-safe, unordered bag that permits duplicate elements but not null.
 * All operations are nonblocking with respect to the bag's contents:
 * remove() returns null when empty, and add() throws if the size limit is reached.
 * Operations may wait to acquire the intrinsic monitor.
 */
@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        final Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private Node<E> head;
    private int size;

    public ConcurrentBag() {
    }

    /**
     * Adds one occurrence of the element.
     *
     * @throws NullPointerException if element is null
     * @throws IllegalStateException if the bag contains 2147483647 elements
     */
    public boolean add(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (this) {
            if (size == 2147483647) {
                throw new IllegalStateException("Bag size limit reached");
            }

            head = new Node<E>(element, head);
            size++;
            return true;
        }
    }

    /**
     * Removes and returns one arbitrary occurrence, or null if empty.
     */
    public E remove() {
        synchronized (this) {
            if (head == null) {
                return null;
            }

            E value = head.value;
            head = head.next;
            size--;
            return value;
        }
    }

    /**
     * Returns the number of occurrences, including duplicates.
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    /**
     * Atomically removes all occurrences.
     */
    public void clear() {
        synchronized (this) {
            head = null;
            size = 0;
        }
    }
}
