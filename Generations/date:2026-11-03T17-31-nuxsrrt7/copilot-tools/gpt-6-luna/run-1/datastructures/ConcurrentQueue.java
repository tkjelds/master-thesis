@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    public void enqueue(E value) {
        if (value == null) {
            throw new NullPointerException("Queue elements cannot be null");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public E dequeue() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            size--;
            return node.value;
        }
    }

    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }
}
