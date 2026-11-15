@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        final Node<E> next;

        Node(E value, Node<E> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private Node<E> top;
    private int size;

    /**
     * Pushes a value, including null, without waiting.
     * Throws IllegalStateException if the size would exceed 2147483647.
     */
    public void push(E value) {
        synchronized (monitor) {
            if (size == 2147483647) {
                throw new IllegalStateException("Stack size limit reached");
            }
            top = new Node<E>(value, top);
            size++;
        }
    }

    /**
     * Removes and returns the top value without waiting.
     * Throws IllegalStateException if the stack is empty.
     */
    public E pop() {
        synchronized (monitor) {
            if (top == null) {
                throw new IllegalStateException("Stack is empty");
            }
            E value = top.value;
            top = top.next;
            size--;
            return value;
        }
    }

    /**
     * Returns the top value without removing it or waiting.
     * Throws IllegalStateException if the stack is empty.
     */
    public E peek() {
        synchronized (monitor) {
            if (top == null) {
                throw new IllegalStateException("Stack is empty");
            }
            return top.value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return top == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            top = null;
            size = 0;
        }
    }
}
