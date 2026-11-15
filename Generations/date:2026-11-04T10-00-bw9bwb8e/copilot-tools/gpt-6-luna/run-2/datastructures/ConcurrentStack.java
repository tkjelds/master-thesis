@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
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
    private Node<T> top;
    private long size;

    public void push(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            top = new Node<T>(value, top);
            size++;
        }
    }

    /**
     * Removes and returns the top element, or returns null if the stack is empty.
     */
    public T pop() {
        synchronized (lock) {
            if (top == null) {
                return null;
            }

            T value = top.value;
            top = top.next;
            size--;
            return value;
        }
    }

    /**
     * Returns the top element without removing it, or null if the stack is empty.
     */
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

    public long size() {
        synchronized (lock) {
            return size;
        }
    }
}
