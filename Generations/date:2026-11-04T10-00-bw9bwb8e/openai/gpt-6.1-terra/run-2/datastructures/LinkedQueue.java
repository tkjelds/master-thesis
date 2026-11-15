@LinkedQueue.ThreadSafe
public class LinkedQueue<E> {
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
     * Adds an element to the tail. Null elements are not permitted.
     * Throws IllegalStateException if the queue has Integer.MAX_VALUE elements.
     */
    public void enqueue(E value) {
        if (!offer(value)) {
            throw new IllegalStateException("Queue is full");
        }
    }

    /**
     * Adds an element to the tail, returning false if the queue is full.
     */
    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
                return false;
            }

            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes and returns the head, or returns null if the queue is empty.
     */
    public E dequeue() {
        return poll();
    }

    public E poll() {
        synchronized (lock) {
            return head == null ? null : removeHead();
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
            return removeHead();
        }
    }

    /**
     * Returns the head without removing it, or null if the queue is empty.
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
    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return node.value;
    }
}
