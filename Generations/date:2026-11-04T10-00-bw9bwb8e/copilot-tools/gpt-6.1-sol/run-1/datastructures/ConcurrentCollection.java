/**
 * A thread-safe, capacity-limited FIFO collection.
 *
 * Null elements are not permitted. offer and poll never wait for space or
 * elements: offer returns false when full, and poll returns null when empty.
 * put and take wait interruptibly until they can complete.
 *
 * All mutable state is protected by a private intrinsic monitor.
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

    private Node<E> head;
    private Node<E> tail;
    private int count;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Creates a collection with the largest representable capacity.
     */
    public ConcurrentCollection() {
        this(2147483647);
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            if (count == capacity) {
                return false;
            }
            enqueue(element);
            return true;
        }
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            while (count == capacity) {
                monitor.wait();
            }
            enqueue(element);
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (count == 0) {
                return null;
            }
            return dequeue();
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (count == 0) {
                monitor.wait();
            }
            return dequeue();
        }
    }

    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

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

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - count;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            count = 0;
            monitor.notifyAll();
        }
    }

    // Caller must hold monitor and ensure count < capacity.
    private void enqueue(E element) {
        Node<E> node = new Node<E>(element);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        count++;
        monitor.notifyAll();
    }

    // Caller must hold monitor and ensure count > 0.
    private E dequeue() {
        Node<E> node = head;
        head = node.next;
        node.next = null;
        count--;
        if (count == 0) {
            tail = null;
        }
        monitor.notifyAll();
        return node.value;
    }
}
