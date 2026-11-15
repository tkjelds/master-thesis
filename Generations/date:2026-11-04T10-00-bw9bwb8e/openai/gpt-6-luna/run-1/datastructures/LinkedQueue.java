@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long count;

    public LinkedQueue() {
        head = new Node<E>(null);
        tail = head;
    }

    /**
     * Adds an item to the end of the queue. Null items are not permitted.
     */
    public void enqueue(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(item);
            tail.next = node;
            tail = node;
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the first item, waiting while the queue is empty.
     */
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head.next == null) {
                lock.wait();
            }

            Node<E> first = head.next;
            E item = first.item;
            first.item = null;
            head = first;
            count--;
            return item;
        }
    }

    /**
     * Removes and returns the first item, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (head.next == null) {
                return null;
            }

            Node<E> first = head.next;
            E item = first.item;
            first.item = null;
            head = first;
            count--;
            return item;
        }
    }

    public long size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head.next == null;
        }
    }
}
