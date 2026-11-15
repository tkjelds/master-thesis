/**
 * A thread-safe, unordered bag that permits duplicate elements but not nulls.
 * Elements are stored in last-in-first-out order; no fairness is guaranteed.
 *
 * All state is protected by a private monitor.
 */
@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private Node<E> head;
    private int size;

    private static final class Node<E> {
        final E element;
        final Node<E> next;

        Node(E element, Node<E> next) {
            this.element = element;
            this.next = next;
        }
    }

    public ConcurrentBag() {
    }

    /**
     * Adds one occurrence without waiting.
     *
     * @throws NullPointerException if element is null
     * @throws IllegalStateException if the bag already contains
     *         2,147,483,647 elements
     */
    public void add(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }

        synchronized (monitor) {
            if (size == 2147483647) {
                throw new IllegalStateException("Maximum bag size reached");
            }

            head = new Node<E>(element, head);
            size++;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns one element, waiting while the bag is empty.
     * If waiting is interrupted, no element is removed by this operation.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }
            return unlinkHead();
        }
    }

    /**
     * Removes and returns one element without waiting, or null if empty.
     */
    public E poll() {
        synchronized (monitor) {
            return head == null ? null : unlinkHead();
        }
    }

    /**
     * Returns the number of elements at the instant this operation executes.
     */
    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    /**
     * Removes all elements atomically.
     */
    public void clear() {
        synchronized (monitor) {
            head = null;
            size = 0;
        }
    }

    // Caller must hold monitor, and head must be non-null.
    private E unlinkHead() {
        E element = head.element;
        head = head.next;
        size--;
        return element;
    }
}
