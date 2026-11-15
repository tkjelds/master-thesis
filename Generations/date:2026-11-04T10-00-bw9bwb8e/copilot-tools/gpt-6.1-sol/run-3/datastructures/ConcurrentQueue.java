/**
 * A bounded, thread-safe FIFO queue that does not permit null elements.
 *
 * put blocks while full; take blocks while empty. Both are interruptible.
 * offer and poll never wait for capacity or elements, respectively.
 * All operations are linearizable.
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
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Inserts an element, waiting while the queue is full.
     * If interrupted while waiting, leaves the queue unchanged.
     */
    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            enqueue(element);
            monitor.notifyAll();
        }
    }

    /**
     * Removes the oldest element, waiting while the queue is empty.
     * If interrupted while waiting, leaves the queue unchanged.
     */
    public E take() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E element = dequeue();
            monitor.notifyAll();
            return element;
        }
    }

    /** Inserts an element if space is available; otherwise returns false. */
    public boolean offer(E element) {
        requireElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            enqueue(element);
            monitor.notifyAll();
            return true;
        }
    }

    /** Removes the oldest element, or returns null if the queue is empty. */
    public E poll() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E element = dequeue();
            monitor.notifyAll();
            return element;
        }
    }

    /** Returns the oldest element without removing it, or null if empty. */
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

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }

    private static void requireElement(Object element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Caller must hold monitor and ensure space is available.
    private void enqueue(E element) {
        Node<E> node = new Node<E>(element);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    // Caller must hold monitor and ensure the queue is nonempty.
    private E dequeue() {
        Node<E> node = head;
        head = node.next;
        node.next = null;
        size--;
        if (head == null) {
            tail = null;
        }
        return node.value;
    }
}
