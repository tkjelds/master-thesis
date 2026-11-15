@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
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
    private Node<T> top;
    private long size;

    public void push(T value) {
        synchronized (lock) {
            top = new Node<T>(value, top);
            size++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the top element, waiting until the stack is nonempty.
     * If interrupted while waiting, this method throws InterruptedException
     * without changing the stack.
     */
    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (top == null) {
                lock.wait();
            }

            T value = top.value;
            top = top.next;
            size--;
            return value;
        }
    }

    /**
     * Returns the top element without removing it, waiting until the stack
     * is nonempty.
     */
    public T peek() throws InterruptedException {
        synchronized (lock) {
            while (top == null) {
                lock.wait();
            }
            return top.value;
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
