/**
 * A bounded, thread-safe FIFO queue.
 *
 * Enqueue blocks while full; dequeue blocks while empty. Blocking operations
 * are interruptible. Null elements are permitted. All queue state is protected
 * by a single private monitor.
 */
@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private final T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void enqueue(E value) throws InterruptedException {
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }

            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            monitor.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            E value = head.value;
            head = head.next;
            size--;
            if (head == null) {
                tail = null;
            }
            monitor.notifyAll();
            return value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public int capacity() {
        return capacity;
    }
}
