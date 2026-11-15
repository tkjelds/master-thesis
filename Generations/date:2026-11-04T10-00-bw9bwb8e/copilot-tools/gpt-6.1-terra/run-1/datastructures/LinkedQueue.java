/**
 * An unbounded, thread-safe FIFO queue.
 *
 * Null elements are not permitted. Enqueue never waits for capacity;
 * dequeue waits while the queue is empty, whereas poll returns null.
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

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    public void enqueue(E value) {
        if (value == null) {
            throw new NullPointerException("value");
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

    /**
     * Removes and returns the first element, waiting if necessary.
     * If interrupted while waiting, throws InterruptedException.
     */
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return removeFirst();
        }
    }

    /** Removes and returns the first element, or null if empty. */
    public E poll() {
        synchronized (lock) {
            return head == null ? null : removeFirst();
        }
    }

    /** Returns the first element without removing it, or null if empty. */
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

    // Called only while holding lock and when the queue is nonempty.
    private E removeFirst() {
        Node<E> first = head;
        head = first.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return first.value;
    }
}
