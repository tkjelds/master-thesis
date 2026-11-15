@LinkedQueue.ThreadSafe
public final class LinkedQueue<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    // Single monitor guarding all mutable state. Every read and write of
    // head, tail, and count happens while holding this lock, which gives
    // mutual exclusion and visibility between threads.
    private final Object lock = new Object();
    private final int capacity;

    // Sentinel head: head.value is always null; the real first element is head.next.
    private Node<T> head;
    private Node<T> tail;
    private int count;

    /**
     * Creates an unbounded queue. enqueue() never blocks for capacity reasons.
     */
    public LinkedQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a bounded queue.
     *
     * Policy:
     *  - enqueue() blocks while the queue is full and resumes when space is freed.
     *  - offer() never blocks; it returns false if the queue is full.
     *  - dequeue() blocks while the queue is empty and resumes when an element arrives.
     *  - poll() never blocks; it returns null if the queue is empty.
     *  - Null elements are rejected with NullPointerException.
     *  - Blocking operations throw InterruptedException if the calling thread is interrupted;
     *    the queue state is left unchanged in that case.
     *
     * @param capacity maximum number of elements, must be positive
     */
    public LinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.head = new Node<>(null);
        this.tail = this.head;
        this.count = 0;
    }

    /**
     * Appends an element, blocking while the queue is full.
     */
    public void enqueue(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }
            appendLocked(item);
            lock.notifyAll();
        }
    }

    /**
     * Appends an element if space is available.
     *
     * @return true if the element was added, false if the queue was full
     */
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (count == capacity) {
                return false;
            }
            appendLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the head element, blocking while the queue is empty.
     */
    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T value = removeLocked();
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Removes and returns the head element if one is present.
     *
     * @return the head element, or null if the queue was empty
     */
    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T value = removeLocked();
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Returns the head element without removing it, or null if empty.
     */
    public T peek() {
        synchronized (lock) {
            if (count == 0) {
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
            return count;
        }
    }

    /**
     * Returns true if the queue currently contains no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    /**
     * Removes all elements and wakes any threads blocked on enqueue.
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
            count = 0;
            lock.notifyAll();
        }
    }

    // Must be called while holding lock.
    private void appendLocked(T item) {
        Node<T> node = new Node<>(item);
        tail.next = node;
        tail = node;
        count++;
    }

    // Must be called while holding lock and count > 0.
    private T removeLocked() {
        Node<T> first = head.next;
        T value = first.value;
        first.value = null;   // the new sentinel must not retain the element
        head = first;         // first becomes the new sentinel
        count--;
        return value;
    }
}
