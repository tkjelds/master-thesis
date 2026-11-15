@ConcurrentStack.ThreadSafe
public class ConcurrentStack<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private int top;
    private final Object monitor;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.elements = new Object[capacity];
        this.top = -1;
        this.monitor = new Object();
    }

    public void push(E value) {
        synchronized (monitor) {
            while (top >= elements.length - 1) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to push", e);
                }
            }

            elements[++top] = value;
            monitor.notifyAll();
        }
    }

    public E pop() {
        synchronized (monitor) {
            while (top < 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to pop", e);
                }
            }

            @SuppressWarnings("unchecked")
            E value = (E) elements[top];
            elements[top] = null;
            top--;
            monitor.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (monitor) {
            while (top < 0) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to peek", e);
                }
            }

            @SuppressWarnings("unchecked")
            E value = (E) elements[top];
            return value;
        }
    }

    public boolean tryPush(E value) {
        synchronized (monitor) {
            if (top >= elements.length - 1) {
                return false;
            }
            elements[++top] = value;
            monitor.notifyAll();
            return true;
        }
    }

    public E tryPop() {
        synchronized (monitor) {
            if (top < 0) {
                return null;
            }

            @SuppressWarnings("unchecked")
            E value = (E) elements[top];
            elements[top] = null;
            top--;
            monitor.notifyAll();
            return value;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return top < 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return top >= elements.length - 1;
        }
    }

    public int size() {
        synchronized (monitor) {
            return top + 1;
        }
    }

    public int capacity() {
        synchronized (monitor) {
            return elements.length;
        }
    }
}
