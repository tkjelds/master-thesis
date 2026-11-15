@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        private E item;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
        }
    }

    // Single intrinsic monitor guarding all mutable state below.
    private final Object lock = new Object();
    private final int capacity;

    // Sentinel-based singly linked list. head is a dummy node; head.next is the first element.
    // All fields are only accessed while holding 'lock', which provides mutual exclusion,
    // visibility, and safe publication of nodes between threads.
    private Node<E> head;
    private Node<E> tail;
    private int size;

    /**
     * Creates an unbounded queue.
     */
    public LinkedQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a bounded queue with the given maximum capacity.
     *
     * @param capacity maximum number of elements, must be positive
     */
    public LinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.head = new Node<E>(null);
        this.tail = head;
        this.size = 0;
    }

    /**
     * Blocking enqueue. If the queue is full, the calling thread waits until
     * space becomes available or the thread is interrupted.
     *
     * @throws NullPointerException if e is null
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            enqueueLocked(e);
            lock.notifyAll();
        }
    }

    /**
     * Blocking dequeue. If the queue is empty, the calling thread waits until
     * an element becomes available or the thread is interrupted.
     *
     * @return the head element
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Non-blocking enqueue. Returns false immediately if the queue is full.
     *
     * @throws NullPointerException if e is null
     */
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            enqueueLocked(e);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Non-blocking dequeue. Returns null immediately if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = dequeueLocked();
            lock.notifyAll();
            return result;
        }
    }

    /**
     * Returns the head element without removing it, or null if empty.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return head.next.item;
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
     * Returns the maximum capacity of this queue.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Removes all elements atomically.
     */
    public void clear() {
        synchronized (lock) {
            head = new Node<E>(null);
            tail = head;
            size = 0;
            lock.notifyAll();
        }
    }

    // Must be called while holding 'lock' and only when size < capacity.
    private void enqueueLocked(E e) {
        Node<E> node = new Node<E>(e);
        tail.next = node;
        tail = node;
        size++;
    }

    // Must be called while holding 'lock' and only when size > 0.
    private E dequeueLocked() {
        Node<E> first = head.next;
        E item = first.item;
        first.item = null;      // help garbage collection; node becomes the new sentinel
        head = first;
        size--;
        return item;
    }
}
