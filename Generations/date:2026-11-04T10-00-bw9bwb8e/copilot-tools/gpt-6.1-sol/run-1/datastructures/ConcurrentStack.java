/**
 * A thread-safe, unbounded LIFO stack. Null elements are permitted.
 * push never waits for capacity; pop and peek wait until an element exists.
 *
 * @param <E> the element type
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

    private final Object monitor = new Object();
    private Node<E> top;
    private long count;

    public void push(E value) {
        synchronized (monitor) {
            top = new Node<E>(value, top);
            count++;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the most recently pushed element.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E pop() throws InterruptedException {
        synchronized (monitor) {
            while (top == null) {
                monitor.wait();
            }

            E value = top.value;
            top = top.next;
            count--;
            return value;
        }
    }

    /**
     * Returns the most recently pushed element without removing it.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public E peek() throws InterruptedException {
        synchronized (monitor) {
            while (top == null) {
                monitor.wait();
            }

            return top.value;
        }
    }

    public long size() {
        synchronized (monitor) {
            return count;
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
            count = 0;
        }
    }
}
