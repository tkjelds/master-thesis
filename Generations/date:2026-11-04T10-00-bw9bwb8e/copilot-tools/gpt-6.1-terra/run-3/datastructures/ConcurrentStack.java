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

    private final Object lock = new Object();
    private Node<E> top;

    // Null values are permitted. Empty pop and peek operations throw.
    public void push(E value) {
        synchronized (lock) {
            top = new Node<E>(value, top);
        }
    }

    public E pop() {
        synchronized (lock) {
            if (top == null) {
                throw new IllegalStateException("Stack is empty");
            }
            E value = top.value;
            top = top.next;
            return value;
        }
    }

    public E peek() {
        synchronized (lock) {
            if (top == null) {
                throw new IllegalStateException("Stack is empty");
            }
            return top.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return top == null;
        }
    }
}
