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

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    public LinkedQueue() {
        head = new Node<E>(null);
        tail = head;
    }

    public void enqueue(E item) {
        if (item == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        Node<E> node = new Node<E>(item);
        synchronized (lock) {
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
            E item = first.item;
            first.item = null;
            head = first;
            size--;
            return item;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (head.next == null) {
                return null;
            }

            Node<E> first = head.next;
            E item = first.item;
            first.item = null;
            head = first;
            size--;
            return item;
        }
    }

    public E peek() {
        synchronized (lock) {
            return head.next == null ? null : head.next.item;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head.next == null;
        }
    }
}
