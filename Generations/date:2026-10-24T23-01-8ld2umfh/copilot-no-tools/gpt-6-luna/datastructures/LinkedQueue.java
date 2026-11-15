@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int count;

    /**
     * Adds an item to the end of the unbounded queue.
     * Null items are not allowed.
     */
    public void enqueue(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(item);
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

    /**
     * Removes and returns the first item, waiting while the queue is empty.
     */
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }

            E item = head.item;
            head = head.next;
            count--;
            if (head == null) {
                tail = null;
            }
            return item;
        }
    }

    /**
     * Adds an item immediately. Returns true when the item is added.
     * Null items are not allowed.
     */
    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        enqueue(item);
        return true;
    }

    /**
     * Removes and returns the first item, or null if the queue is empty.
     */
    public E poll() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            E item = head.item;
            head = head.next;
            count--;
            if (head == null) {
                tail = null;
            }
            return item;
        }
    }

    /**
     * Returns the first item without removing it, or null if the queue is empty.
     */
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
