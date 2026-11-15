@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final int capacity;
    private int size;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    // Blocks while the stack is full. Interruption is propagated to the caller.
    public void push(T value) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            elements[size++] = value;
            notifyAll();
        }
    }

    // Blocks while the stack is empty. Interruption is propagated to the caller.
    public T pop() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            int index = --size;
            T value = (T) elements[index];
            elements[index] = null;
            notifyAll();
            return value;
        }
    }

    // Blocks while the stack is empty. Interruption is propagated to the caller.
    public T peek() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            return (T) elements[size - 1];
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public int capacity() {
        return capacity;
    }
}
