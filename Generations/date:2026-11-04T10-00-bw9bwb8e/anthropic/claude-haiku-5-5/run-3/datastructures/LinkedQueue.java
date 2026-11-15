@LinkedQueue.ThreadSafe
public final class LinkedQueue<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        private T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
            this.next = null;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // Sentinel head: head.next is the first real element.
    // All fields below are guarded by 'lock'.
    private Node<T> head;
    private Node<T> tail;
    private int size;

    /**
     * Creates an unbounded queue. put() never blocks; take() blocks while empty.
     */
    public LinkedQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a bounded queue with the given capacity (must be positive).
     * put() blocks while full; take() blocks while empty.
     * offer() returns false when full; poll() returns null when empty.
     */
    public LinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.head = new Node<T>(null);
        this.tail = head;
        this.size = 0;
    }

    /**
     * Appends an element, blocking while the queue is full.
     * Policy: waits until space is available; throws InterruptedException if interrupted.
     */
    public void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            enqueueLocked(item);
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the head element, blocking while the queue is empty.
     * Policy: waits until an element is available; throws InterruptedException if interrupted.
     */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T item = dequeueLocked();
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Non-blocking insert. Returns false if the queue is full.
     */
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            enqueueLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Non-blocking removal. Returns null if the queue is empty.
     */
    public T poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T item = dequeueLocked();
            lock.notifyAll();
            return item;
        }
    }

    /**
     * Returns the head element without removing it, or null if empty.
     */
    public T peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return head.next.value;
        }
    }

    /**
     * Returns the current number of elements.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the queue currently holds no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the configured capacity (Integer.MAX_VALUE for unbounded queues).
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Removes all elements and wakes any blocked producers.
     */
    public void clear() {
        synchronized (lock) {
            Node<T> current = head.next;
            while (current != null) {
                Node<T> next = current.next;
                current.value = null;
                current.next = null;
                current = next;
            }
            head.next = null;
            tail = head;
            size = 0;
            lock.notifyAll();
        }
    }

    // Must be called while holding 'lock'.
    private void enqueueLocked(T item) {
        Node<T> node = new Node<T>(item);
        tail.next = node;
        tail = node;
        size++;
    }

    // Must be called while holding 'lock' and only when size > 0.
    private T dequeueLocked() {
        Node<T> first = head.next;
        T item = first.value;
        head.next = first.next;
        if (head.next == null) {
            tail = head;
        }
        first.value = null;
        first.next = null;
        size--;
        return item;
    }
}
