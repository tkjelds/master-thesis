@ConcurrentStack.ThreadSafe
public final class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int count;

    public ConcurrentStack() {
        this(16);
    }

    public ConcurrentStack(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        elements = new Object[initialCapacity];
    }

    /**
     * Pushes an element onto the stack. The stack grows as needed.
     * Null elements are permitted.
     */
    public void push(E element) {
        synchronized (this) {
            if (count == elements.length) {
                grow();
            }
            elements[count++] = element;
            notifyAll();
        }
    }

    /**
     * Removes and returns the top element. If the stack is empty, waits until
     * an element is pushed or this thread is interrupted.
     */
    @SuppressWarnings("unchecked")
    public E pop() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            int top = --count;
            Object element = elements[top];
            elements[top] = null;
            return (E) element;
        }
    }

    /**
     * Returns the top element without removing it. If the stack is empty,
     * waits until an element is pushed or this thread is interrupted.
     */
    @SuppressWarnings("unchecked")
    public E peek() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            return (E) elements[count - 1];
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    private void grow() {
        int oldCapacity = elements.length;
        int newCapacity = oldCapacity << 1;
        if (newCapacity <= oldCapacity) {
            newCapacity = Integer.MAX_VALUE;
        }

        Object[] expanded = new Object[newCapacity];
        for (int i = 0; i < oldCapacity; i++) {
            expanded[i] = elements[i];
        }
        elements = expanded;
    }
}
