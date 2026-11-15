@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    /**
     * Adds a non-null element. Throws IllegalStateException if the queue
     * contains Integer.MAX_VALUE elements.
     */
    public void enqueue(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }

        Node<E> node = new Node<E>(element);
        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("queue is full");
            }
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the first element, or null if the queue is empty.
     */
    public E dequeue() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return removeFirst();
        }
    }

    /**
     * Waits until an element is available, then removes and returns it.
     */
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return removeFirst();
        }
    }

    /**
     * Returns the first element without removing it, or null if empty.
     */
    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    // Called only while holding lock and when the queue is nonempty.
    private E removeFirst() {
        Node<E> first = head;
        head = first.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return first.value;
    }
}
