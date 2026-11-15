@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        final Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> top;
    private int size;

    /**
     * Creates a stack with a maximum capacity of 2147483647 elements.
     */
    public ConcurrentStack() {
        this(2147483647);
    }

    /**
     * Creates a stack with the specified positive capacity.
     */
    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Pushes a value, including null. Throws IllegalStateException if full;
     * this operation does not wait for space.
     */
    public void push(E value) {
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("Stack is full");
            }
            top = new Node<E>(value, top);
            size++;
        }
    }

    /**
     * Removes and returns the top value. Throws IllegalStateException if empty;
     * this operation does not wait for an element.
     */
    public E pop() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Stack is empty");
            }
            E value = top.value;
            top = top.next;
            size--;
            return value;
        }
    }

    /**
     * Returns the top value without removing it.
     * Throws IllegalStateException if empty.
     */
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Stack is empty");
            }
            return top.value;
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
            return size == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            top = null;
            size = 0;
        }
    }
}
