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

    private final Object lock = new Object();
    private Node<T> top;
    private int size;

    /**
     * Pushes a non-null value. This unbounded stack never waits for capacity.
     *
     * @throws IllegalArgumentException if value is null
     * @throws IllegalStateException if the size limit is reached
     */
    public void push(T value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }

        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("stack size limit reached");
            }
            top = new Node<T>(value, top);
            size++;
        }
    }

    /**
     * Removes and returns the top value, or returns null if the stack is empty.
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
     * Returns the top value without removing it, or null if the stack is empty.
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

    public int size() {
        synchronized (lock) {
            return size;
        }
    }
}
