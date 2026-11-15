/**
 * A thread-safe bag that permits duplicate, non-null elements.
 *
 * Operations never wait for the bag to become nonempty or gain space.
 * add returns false for null elements or when size reaches 2147483647.
 * remove returns null when empty; otherwise it removes an arbitrary element.
 * No iteration or removal ordering is guaranteed.
 */
@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private static final int MAX_SIZE = 2147483647;

    private static final class Monitor {
    }

    private static final class Node<T> {
        final T value;
        final Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Monitor monitor = new Monitor();
    private Node<E> head;
    private int size;

    public ConcurrentBag() {
    }

    /**
     * Adds one occurrence. Returns false without changing the bag if the
     * element is null or the bag has reached its maximum size.
     */
    public boolean add(E element) {
        synchronized (monitor) {
            if (element == null || size == MAX_SIZE) {
                return false;
            }

            Node<E> node = new Node<E>(element, head);
            head = node;
            size++;
            return true;
        }
    }

    /**
     * Removes one arbitrary occurrence, or returns null if the bag is empty.
     */
    public E remove() {
        synchronized (monitor) {
            if (head == null) {
                return null;
            }

            E element = head.value;
            head = head.next;
            size--;
            return element;
        }
    }

    /**
     * Returns the number of occurrences, including duplicates.
     */
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
            head = null;
            size = 0;
        }
    }
}
