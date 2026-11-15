@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void enqueue(E value) throws InterruptedException {
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }

            Node<E> node = new Node<>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            monitor.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            Node<E> node = head;
            head = node.next;
            size--;
            if (head == null) {
                tail = null;
            }
            monitor.notifyAll();
            return node.value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }
}
