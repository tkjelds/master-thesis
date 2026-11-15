/**
 * A bounded, thread-safe FIFO queue.
 *
 * Enqueue blocks while full; dequeue blocks while empty.
 * Both blocking operations are interruptible. Null elements are allowed.
 *
 * Save as ConcurrentQueue.java.
 */
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

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int count;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Appends an element, waiting until space is available.
     * Interruption while waiting leaves the queue unchanged by this call.
     */
    public void enqueue(E value) throws InterruptedException {
        synchronized (monitor) {
            while (count == capacity) {
                monitor.wait();
            }

            Node<E> node = new Node<E>(value);
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

    /**
     * Removes the oldest element, waiting until an element is available.
     * Interruption while waiting leaves the queue unchanged by this call.
     */
    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }

            Node<E> node = head;
            head = node.next;
            node.next = null;
            count--;

            if (head == null) {
                tail = null;
            }

            monitor.notifyAll();
            return node.value;
        }
    }

    /** Returns a snapshot of the number of queued elements. */
    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return count == capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - count;
        }
    }

    public int capacity() {
        return capacity;
    }
}
