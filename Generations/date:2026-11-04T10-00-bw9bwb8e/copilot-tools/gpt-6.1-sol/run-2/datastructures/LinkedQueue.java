/**
 * An unbounded, thread-safe FIFO linked queue.
 *
 * Operations never wait for the queue to become nonempty.
 * dequeue() and peek() return null when empty. Null elements are allowed,
 * so a null result can also represent a queued null element.
 */
@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    // All queue state is guarded by this queue's intrinsic monitor.
    // An empty queue has both head and tail null; otherwise tail.next is null.
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

            Node<E> removed = head;
            head = removed.next;
            removed.next = null;

            if (head == null) {
                tail = null;
            }

            return removed.value;
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

    /**
     * Returns the number of elements, saturated at the largest int value.
     * Traversal holds the monitor, giving a consistent snapshot.
     */
    public int size() {
        synchronized (this) {
            int count = 0;
            Node<E> current = head;
            while (current != null && count < 0x7fffffff) {
                count++;
                current = current.next;
            }
            return count;
        }
    }

    public void clear() {
        synchronized (this) {
            head = null;
            tail = null;
        }
    }
}
