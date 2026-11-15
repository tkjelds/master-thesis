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
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        elements = new Object[initialCapacity];
    }

    public void push(E item) {
        synchronized (lock) {
            if (size == elements.length) {
                int newCapacity = elements.length * 2;
                if (newCapacity <= elements.length) {
                    throw new OutOfMemoryError("Stack capacity exceeded");
                }
                Object[] expanded = new Object[newCapacity];
                System.arraycopy(elements, 0, expanded, 0, size);
                elements = expanded;
            }
            elements[size++] = item;
            lock.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public E pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            int index = --size;
            E item = (E) elements[index];
            elements[index] = null;
            return item;
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
}
