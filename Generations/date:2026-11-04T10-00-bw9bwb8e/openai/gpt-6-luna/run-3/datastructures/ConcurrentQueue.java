@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<T> head;
    private Node<T> tail;
    private long size;

    public ConcurrentQueue() {
        head = new Node<T>(null);
        tail = head;
    }

    public void enqueue(T value) {
        if (value == null) {
            throw new NullPointerException("null values are not supported");
        }

        synchronized (lock) {
            Node<T> node = new Node<T>(value);
            tail.next = node;
            tail = node;
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the queue head, waiting until an item is available.
     */
    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head.next == null) {
                lock.wait();
            }

            Node<T> first = head.next;
            T value = first.value;
            first.value = null;
            head = first;
            size--;
            return value;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }
}
