@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object monitor = new Object();
    private Node<T> head;
    private Node<T> tail;
    private long count;

    /**
     * Adds an item to the end of the queue. This operation does not block.
     *
     * @throws IllegalArgumentException if item is null
     */
    public void enqueue(T item) {
        if (item == null) {
            throw new IllegalArgumentException("Queue does not accept null items");
        }

        Node<T> node = new Node<>(item);
        synchronized (monitor) {
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            count++;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the next item, waiting until the queue is nonempty.
     *
     * @throws InterruptedException if interrupted while waiting; the queue is unchanged
     */
    public T dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }

            Node<T> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            count--;
            return node.item;
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
}
