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

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    /** Adds a non-null value. This unbounded operation does not wait for space. */
    public void enqueue(E value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }

        Node<E> node = new Node<E>(value);
        synchronized (lock) {
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            lock.notifyAll();
        }
    }

    /** Removes the oldest value, waiting while the queue is empty. */
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return removeHead();
        }
    }

    /** Removes the oldest value, or returns null if the queue is empty. */
    public E poll() {
        synchronized (lock) {
            return head == null ? null : removeHead();
        }
    }

    /** Returns the oldest value without removing it, or null if empty. */
    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }

    private E removeHead() {
        E value = head.value;
        head = head.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return value;
    }
}
