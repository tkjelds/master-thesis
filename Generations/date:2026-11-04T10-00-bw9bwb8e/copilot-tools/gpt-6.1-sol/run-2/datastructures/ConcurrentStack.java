/**
 * A thread-safe, unbounded LIFO stack that permits null elements.
 * pop() and peek() block while the stack is empty and are interruptible.
 * Elements are safely published through the stack's intrinsic monitor.
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

    private Node<E> head;

    public ConcurrentStack() {
    }

    public void push(E value) {
        synchronized (this) {
            head = new Node<E>(value, head);
            notifyAll();
        }
    }

    public E pop() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }

            E value = head.value;
            head = head.next;
            return value;
        }
    }

    public E peek() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }

            return head.value;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }
}
