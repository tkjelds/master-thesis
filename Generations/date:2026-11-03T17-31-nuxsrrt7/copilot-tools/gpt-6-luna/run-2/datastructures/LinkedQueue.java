@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    public LinkedQueue() {
        head = new Node<>(null);
        tail = head;
    }

    public void enqueue(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(value);
            tail.next = node;
            tail = node;
            size++;
            lock.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head.next == null) {
                lock.wait();
            }

            Node<E> first = head.next;
            head = first;
            E value = first.value;
            first.value = null;
            size--;
            return value;
        }
    }

    public E tryDequeue() {
        synchronized (lock) {
            if (head.next == null) {
                return null;
            }

            Node<E> first = head.next;
            head = first;
            E value = first.value;
            first.value = null;
            size--;
            return value;
        }
    }

    public E peek() {
        synchronized (lock) {
            return head.next == null ? null : head.next.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }
}
