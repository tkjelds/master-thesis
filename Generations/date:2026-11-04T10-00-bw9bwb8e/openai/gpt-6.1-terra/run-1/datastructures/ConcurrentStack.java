/**
 * A bounded, thread-safe stack.
 *
 * push blocks while the stack is full; pop and peek block while it is empty.
 * Blocking operations throw InterruptedException if interrupted while waiting,
 * without modifying the stack. Null elements are not permitted.
 */
@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Object[] elements;
    private int size;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void push(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (monitor) {
            while (size == elements.length) {
                monitor.wait();
            }
            elements[size] = element;
            size++;
            monitor.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public E pop() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            int index = --size;
            E element = (E) elements[index];
            elements[index] = null;
            monitor.notifyAll();
            return element;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return (E) elements[size - 1];
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int capacity() {
        return elements.length;
    }
}
