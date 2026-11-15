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

    private final Object monitor = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long count;

    public void enqueue(E value) {
        Node<E> node = new Node<E>(value);

        synchronized (monitor) {
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            count++;
            monitor.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }

            Node<E> node = head;
            head = node.next;
            node.next = null;
            count--;

            if (head == null) {
                tail = null;
            }

            return node.value;
        }
    }

    public long size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }
}
