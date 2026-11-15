@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private Object[] elements;
    private int size;

    public ConcurrentStack() {
        this(16);
    }

    public ConcurrentStack(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        elements = new Object[initialCapacity];
    }

    public void push(T value) {
        synchronized (this) {
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
            notifyAll();
        }
    }

    public T pop() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            @SuppressWarnings("unchecked")
            T value = (T) elements[--size];
            elements[size] = null;
            return value;
        }
    }

    public T peek() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            @SuppressWarnings("unchecked")
            T value = (T) elements[size - 1];
            return value;
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
}
