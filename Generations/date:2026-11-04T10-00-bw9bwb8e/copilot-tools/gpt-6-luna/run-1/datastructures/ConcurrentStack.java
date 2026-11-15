@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private long size;

    public void push(E value) {
        if (value == null) {
            throw new NullPointerException("ConcurrentStack does not accept null");
        }

        Node<E> node = new Node<>(value);
        synchronized (monitor) {
            node.next = head;
            head = node;
            size++;
            monitor.notifyAll();
        }
    }

    public E pop() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }

            E value = head.value;
            head = head.next;
            size--;
            return value;
        }
    }

    public E peek() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }

            return head.value;
        }
    }

    public long size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            size = 0;
        }
    }
}
