/**
 * A thread-safe, bounded FIFO collection.
 *
 * Null elements are not permitted.
 * put and take block while full or empty, respectively, and are interruptible.
 * offer returns false when full; poll and peek return null when empty.
 * Waiting threads are not guaranteed FIFO scheduling.
 */
@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private static final class Monitor {
    }

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Monitor monitor = new Monitor();
    private final int capacity;

    // All mutable state is accessed while holding monitor.
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void put(E value) throws InterruptedException {
        requireNonNull(value);

        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            enqueue(value);
            monitor.notifyAll();
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E value = dequeue();
            monitor.notifyAll();
            return value;
        }
    }

    public boolean offer(E value) {
        requireNonNull(value);

        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            enqueue(value);
            monitor.notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E value = dequeue();
            monitor.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
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

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
            monitor.notifyAll();
        }
    }

    private void requireNonNull(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Caller must hold monitor.
    private void enqueue(E value) {
        Node<E> node = new Node<E>(value);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    // Caller must hold monitor, and the collection must not be empty.
    private E dequeue() {
        Node<E> node = head;
        head = node.next;
        node.next = null;
        size--;

        if (size == 0) {
            tail = null;
        }
        return node.value;
    }
}
