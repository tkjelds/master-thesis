/**
 * A thread-safe, unbounded linked FIFO queue.
 * Null elements are prohibited. Enqueue never waits for capacity.
 * Dequeue waits interruptibly until an element is available.
 * Poll and peek return null when the queue is empty.
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

    private final Object monitor = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long count;

    public LinkedQueue() {
    }

    public void enqueue(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        Node<E> node = new Node<E>(value);
        synchronized (monitor) {
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            count++;
            monitor.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }
            return removeHead();
        }
    }

    public E poll() {
        synchronized (monitor) {
            return head == null ? null : removeHead();
        }
    }

    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    public long size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    // Called only while holding monitor and with a nonempty queue.
    private E removeHead() {
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
