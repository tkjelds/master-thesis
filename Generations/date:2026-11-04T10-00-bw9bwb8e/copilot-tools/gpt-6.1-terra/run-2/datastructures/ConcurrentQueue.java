@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
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

    // The queue is unbounded, and null elements are not permitted.
    public void enqueue(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        Node<E> node = new Node<E>(value);
        synchronized (lock) {
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            lock.notifyAll();
        }
    }

    // Waits for an element; interruption leaves the queue unchanged.
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return removeHead();
        }
    }

    // Returns null immediately if the queue is empty.
    public E poll() {
        synchronized (lock) {
            return head == null ? null : removeHead();
        }
    }

    // Returns null if the queue is empty, without removing an element.
    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }

    // Called only while holding lock and when head is non-null.
    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        }
        return node.value;
    }
}
