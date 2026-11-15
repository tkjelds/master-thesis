@ConcurrentStack.ThreadSafe
public final class ConcurrentStack {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private int[] elements = new int[16];
    private int size;

    public void push(int value) {
        synchronized (monitor) {
            if (size == elements.length) {
                if (elements.length == Integer.MAX_VALUE) {
                    throw new OutOfMemoryError("Stack capacity exceeded");
                }

                int newCapacity = elements.length <= Integer.MAX_VALUE / 2
                        ? elements.length * 2
                        : Integer.MAX_VALUE;
                int[] expanded = new int[newCapacity];
                for (int i = 0; i < size; i++) {
                    expanded[i] = elements[i];
                }
                elements = expanded;
            }

            elements[size++] = value;
            monitor.notifyAll();
        }
    }

    /**
     * Removes and returns the top value, waiting until a value is available.
     */
    public int pop() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return elements[--size];
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
}
