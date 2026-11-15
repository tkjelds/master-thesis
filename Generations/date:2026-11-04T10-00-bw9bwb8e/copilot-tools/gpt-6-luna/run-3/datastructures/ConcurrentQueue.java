@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private final T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void enqueue(E element) {
        if (element == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(element);
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

    public E poll() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

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
}
