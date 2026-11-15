/**
 * A bounded, thread-safe FIFO collection.
 *
 * Null elements are not permitted. put and take block while full or empty,
 * respectively, and support interruption. offer and poll never wait for
 * capacity or elements; offer returns false when full, and poll returns null
 * when empty. All operations use a single intrinsic monitor.
 */
@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
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
    private int size;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            enqueue(element);
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            enqueue(element);
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return dequeue();
        }
    }

    public E poll() {
        synchronized (monitor) {
            return size == 0 ? null : dequeue();
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

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
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

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
            monitor.notifyAll();
        }
    }

    // Caller must hold monitor and ensure capacity is available.
    private void enqueue(E element) {
        Node<E> node = new Node<E>(element);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
        monitor.notifyAll();
    }

    // Caller must hold monitor and ensure an element is available.
    private E dequeue() {
        Node<E> node = head;
        head = node.next;
        node.next = null;
        size--;
        if (size == 0) {
            tail = null;
        }
        monitor.notifyAll();
        return node.value;
    }
}
