/**
 * A thread-safe, non-blocking stack. Null elements are permitted.
 * Pop and peek throw IllegalStateException when the stack is empty.
 * Push throws IllegalStateException if the stack contains Integer.MAX_VALUE
 * elements. Memory exhaustion may also prevent a push.
 */
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
    private int size;

    public void push(E value) {
        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Stack is full");
            }
            top = new Node<E>(value, top);
            size++;
        }
    }

    public E pop() {
        synchronized (lock) {
            if (top == null) {
                throw new IllegalStateException("Stack is empty");
            }
            E value = top.value;
            top = top.next;
            size--;
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

    public int size() {
        synchronized (lock) {
            return size;
        }
    }
}
