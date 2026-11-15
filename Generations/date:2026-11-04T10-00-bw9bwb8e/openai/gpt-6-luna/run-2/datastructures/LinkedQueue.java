@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private final T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long count;

    public LinkedQueue() {
    }

    /**
     * Adds an element to the end of the queue. Null elements are not allowed.
     */
    public void enqueue(E element) {
        if (element == null) {
            throw new IllegalArgumentException("Null elements are not allowed");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(element);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            count++;
        }
    }

    /**
     * Removes and returns the first element, or returns null if the queue is empty.
     * This operation never blocks.
     */
    public E dequeue() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> removed = head;
            head = removed.next;
            removed.next = null;

            if (head == null) {
                tail = null;
            }

            count--;
            return removed.value;
        }
    }

    /**
     * Returns the first element without removing it, or null if the queue is empty.
     */
    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public long size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }
}
