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
    private int count;

    public void enqueue(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(item);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            count++;
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
            if (head == null) {
                tail = null;
            }
            count--;

            E item = node.item;
            node.item = null;
            node.next = null;
            return item;
        }
    }

    public E tryDequeue() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> node = head;
            head = node.next;
            if (head == null) {
                tail = null;
            }
            count--;

            E item = node.item;
            node.item = null;
            node.next = null;
            return item;
        }
    }

    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }
}
