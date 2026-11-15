/**
 * A thread-safe, FIFO linked queue.
 *
 * Enqueue never waits for space. Dequeue and peek return null immediately
 * when empty. Null elements are permitted, so a null result can also
 * represent a queued null element.
 *
 * Each operation is atomic with respect to other operations on this queue.
 */
@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    // All queue state is guarded by this queue's intrinsic monitor.
    private Node<E> head;
    private Node<E> tail;

    public LinkedQueue() {
    }

    public void enqueue(E value) {
        Node<E> node = new Node<E>(value);

        synchronized (this) {
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
        }
    }

    public E dequeue() {
        synchronized (this) {
            if (head == null) {
                return null;
            }

            Node<E> node = head;
            head = node.next;
            node.next = null;

            if (head == null) {
                tail = null;
            }

            return node.value;
        }
    }

    public E peek() {
        synchronized (this) {
            return head == null ? null : head.value;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (this) {
            head = null;
            tail = null;
        }
    }
}
