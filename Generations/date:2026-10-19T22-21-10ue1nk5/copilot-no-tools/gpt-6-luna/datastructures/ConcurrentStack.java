@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private final Node<E> next;

        private Node(E value, Node<E> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private Node<T> top;
    private long size;

    public void push(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        synchronized (monitor) {
            top = new Node<>(value, top);
            size++;
            monitor.notifyAll();
        }
    }

    public T pop() throws InterruptedException {
        synchronized (monitor) {
            while (top == null) {
                monitor.wait();
            }

            T value = top.value;
            top = top.next;
            size--;
            return value;
        }
    }

    public T peek() throws InterruptedException {
        synchronized (monitor) {
            while (top == null) {
                monitor.wait();
            }

            return top.value;
        }
    }

    public long size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return top == null;
        }
    }
}
