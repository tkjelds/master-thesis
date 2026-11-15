@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
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
    private int size;

    public void enqueue(E value) {
        if (value == null) {
            throw new NullPointerException("null values are not supported");
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
            return size == 0;
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
