/**
 * A fixed-capacity, thread-safe LIFO stack.
 *
 * Pushes block while the stack is full. Pops and peeks block while it is empty.
 * These operations can be interrupted; interruption leaves the stack unchanged
 * if the operation is still waiting.
 */
@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final Object lock = new Object();
    private int size;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void push(E value) throws InterruptedException {
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            elements[size++] = value;
            lock.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public E pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E value = (E) elements[--size];
            elements[size] = null;
            lock.notifyAll();
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return (E) elements[size - 1];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    public int capacity() {
        synchronized (lock) {
            return elements.length;
        }
    }
}
