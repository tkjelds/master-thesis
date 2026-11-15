@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long count;

    public ConcurrentQueue() {
    }

    /**
     * Adds an element to this unbounded queue. This operation never waits.
     * Null elements are not permitted.
     */
    public void enqueue(E element) {
        if (element == null) {
            throw new IllegalArgumentException("Null elements are not permitted");
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
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the first element, waiting while the queue is empty.
     */
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }

            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            count--;
            return node.value;
        }
    }

    /**
     * Removes and returns the first element, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            count--;
            return node.value;
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
