@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Object[] elements;
    private int size;

    public ConcurrentStack() {
        this(16);
    }

    public ConcurrentStack(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity cannot be negative");
        }
        elements = new Object[initialCapacity];
    }

    /**
     * Pushes an element onto the stack. The stack grows as needed.
     */
    public void push(E element) {
        synchronized (lock) {
            if (size == elements.length) {
                grow();
            }
            elements[size++] = element;
        }
    }

    /**
     * Removes and returns the top element.
     *
     * @throws IllegalStateException if the stack is empty
     */
    @SuppressWarnings("unchecked")
    public E pop() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Cannot pop from an empty stack");
            }
            int top = --size;
            E element = (E) elements[top];
            elements[top] = null;
            return element;
        }
    }

    /**
     * Returns the top element without removing it.
     *
     * @throws IllegalStateException if the stack is empty
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Cannot peek at an empty stack");
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

    private void grow() {
        int oldCapacity = elements.length;
        int newCapacity;

        if (oldCapacity == 0) {
            newCapacity = 1;
        } else if (oldCapacity > Integer.MAX_VALUE / 2) {
            newCapacity = Integer.MAX_VALUE;
        } else {
            newCapacity = oldCapacity * 2;
        }

        if (newCapacity <= oldCapacity) {
            throw new OutOfMemoryError("Stack capacity exceeded");
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < size; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
