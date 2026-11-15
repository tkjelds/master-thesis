@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        final Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<T> top;

    // Null values are rejected. Operations never wait for the stack to change.
    public boolean push(T value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            top = new Node<T>(value, top);
            return true;
        }
    }

    // Returns null if the stack is empty.
    public T pop() {
        synchronized (lock) {
            if (top == null) {
                return null;
            }
            T value = top.value;
            top = top.next;
            return value;
        }
    }

    // Returns null if the stack is empty.
    public T peek() {
        synchronized (lock) {
            return top == null ? null : top.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return top == null;
        }
    }
}
