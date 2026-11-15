@LinkedQueue.ThreadSafe
public final class LinkedQueue<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private long size;

    public LinkedQueue() {
        head = null;
        tail = null;
        size = 0;
    }

    /**
     * Adds a value to the end of the queue. This operation never blocks.
     * Null values are not permitted.
     */
    public void enqueue(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not permitted");
        }

        synchronized (this) {
            Node<T> node = new Node<T>(value);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
            notifyAll();
        }
    }

    /**
     * Removes and returns the first value, waiting until the queue is nonempty.
     * If interrupted while waiting, this method throws InterruptedException
     * without removing an element.
     */
    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }

            Node<T> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            size--;
            return node.value;
        }
    }

    /**
     * Removes and returns the first value, or returns null if the queue is empty.
     */
    public T poll() {
        synchronized (this) {
            if (head == null) {
                return null;
            }

            Node<T> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            size--;
            return node.value;
        }
    }

    public long size() {
        synchronized (this) {
            return size;
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
            size = 0;
        }
    }
}
