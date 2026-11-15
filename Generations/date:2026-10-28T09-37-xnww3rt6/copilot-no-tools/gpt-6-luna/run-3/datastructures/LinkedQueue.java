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

    public void enqueue(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        Node<E> node = new Node<>(value);
        synchronized (lock) {
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

    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return removeHead();
        }
    }

    /**
     * Removes and returns the head, or returns null if the queue is empty.
     */
    public E tryDequeue() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return removeHead();
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

    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        size--;

        if (head == null) {
            tail = null;
        }

        return node.value;
    }
}
