@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public LinkedQueue() {
        head = tail = new Node<>(null);
    }

    /**
     * Adds an element to the end of this unbounded queue.
     *
     * @throws NullPointerException if element is null
     * @throws IllegalStateException if the queue size cannot be represented
     */
    public void enqueue(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("queue is too large");
            }

            Node<E> node = new Node<>(element);
            tail.next = node;
            tail = node;
            size++;
        }
    }

    /**
     * Removes and returns the first element, or returns null if the queue is empty.
     */
    public E dequeue() {
        synchronized (lock) {
            Node<E> first = head.next;
            if (first == null) {
                return null;
            }

            E value = first.value;
            head.next = null;
            head = first;
            head.value = null;
            size--;
            return value;
        }
    }

    /**
     * Returns the first element without removing it, or null if the queue is empty.
     */
    public E peek() {
        synchronized (lock) {
            Node<E> first = head.next;
            return first == null ? null : first.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = tail = new Node<>(null);
            size = 0;
        }
    }
}
