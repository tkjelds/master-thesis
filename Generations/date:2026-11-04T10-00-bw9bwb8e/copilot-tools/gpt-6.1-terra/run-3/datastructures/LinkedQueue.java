@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Monitor {
    }

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Monitor monitor = new Monitor();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    /*
     * Returns false for null values or if the size cannot be represented.
     * Otherwise, adds the value to the tail and returns true.
     */
    public boolean enqueue(E value) {
        if (value == null) {
            return false;
        }

        synchronized (monitor) {
            if (size == 9223372036854775807L) {
                return false;
            }

            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            return true;
        }
    }

    /* Removes and returns the head, or returns null if the queue is empty. */
    public E dequeue() {
        synchronized (monitor) {
            if (head == null) {
                return null;
            }

            E value = head.value;
            head = head.next;
            size--;
            if (head == null) {
                tail = null;
            }
            return value;
        }
    }

    /* Returns the head without removing it, or null if the queue is empty. */
    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    public long size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
        }
    }
}
