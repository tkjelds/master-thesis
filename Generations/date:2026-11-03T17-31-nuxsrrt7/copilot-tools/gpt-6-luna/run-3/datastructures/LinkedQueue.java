@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void enqueue(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> node = new Node<>(value);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
            lock.notifyAll();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }

            E value = head.value;
            head = head.next;
            size--;
            if (head == null) {
                tail = null;
            }
            return value;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            E value = head.value;
            head = head.next;
            size--;
            if (head == null) {
                tail = null;
            }
            return value;
        }
    }

    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }
}
