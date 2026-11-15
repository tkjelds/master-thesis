/**
 * An unbounded, thread-safe FIFO queue.
 *
 * Null elements are not permitted. poll() and peek() return null when the
 * queue is empty; take() waits until an element is available. Waiting threads
 * have no guaranteed ordering.
 */
@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    /**
     * Adds an element to the tail. Returns true after the element is added.
     *
     * @throws NullPointerException if element is null
     */
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        Node<E> node = new Node<E>(element);
        synchronized (monitor) {
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    /** Removes and returns the head, or null if the queue is empty. */
    public E poll() {
        synchronized (monitor) {
            if (head == null) {
                return null;
            }
            return removeHead();
        }
    }

    /** Returns the head without removing it, or null if the queue is empty. */
    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    /**
     * Waits for an element, then removes and returns the head.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }
            return removeHead();
        }
    }

    /** Returns the number of elements currently in the queue. */
    public long size() {
        synchronized (monitor) {
            return size;
        }
    }

    /** Returns whether the queue is currently empty. */
    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return node.value;
    }
}
