@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value, Node<E> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private long size;

    public void push(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (lock) {
            head = new Node<E>(value, head);
            size++;
            lock.notifyAll();
        }
    }

    public E pop() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }

            Node<E> node = head;
            head = node.next;
            size--;
            return node.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }
}
