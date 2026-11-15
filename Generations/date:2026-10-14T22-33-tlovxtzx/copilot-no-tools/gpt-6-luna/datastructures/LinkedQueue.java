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

    private Node<E> head;
    private Node<E> tail;
    private int size;

    public LinkedQueue() {
        head = null;
        tail = null;
        size = 0;
    }

    public void enqueue(E value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }

        Node<E> node = new Node<>(value);
        synchronized (this) {
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
            notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }

            Node<E> removed = head;
            head = removed.next;
            size--;

            if (head == null) {
                tail = null;
            }

            return removed.value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }
}
