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
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        elements = new Object[initialCapacity];
    }

    public void push(E value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }

        synchronized (lock) {
            if (size == elements.length) {
                int newCapacity = elements.length <= Integer.MAX_VALUE / 2
                        ? elements.length * 2
                        : Integer.MAX_VALUE;
                if (newCapacity == elements.length) {
                    throw new OutOfMemoryError("Stack capacity exceeded");
                }

                Object[] expanded = new Object[newCapacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = elements[i];
                }
                elements = expanded;
            }

            elements[size++] = value;
        }
    }

    @SuppressWarnings("unchecked")
    public E pop() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }

            int top = --size;
            Object value = elements[top];
            elements[top] = null;
            return (E) value;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
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
}
