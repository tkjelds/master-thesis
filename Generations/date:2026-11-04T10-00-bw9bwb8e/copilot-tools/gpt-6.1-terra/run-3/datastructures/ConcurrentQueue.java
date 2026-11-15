/**
 * An unbounded FIFO queue. Null elements are permitted.
 * Dequeue waits while the queue is empty and responds to interruption.
 */
@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Monitor {}

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

    public void enqueue(E value) {
        Node<E> node = new Node<E>(value);
        synchronized (monitor) {
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            monitor.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }

            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            return node.value;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }
}
