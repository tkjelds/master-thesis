public class ConcurrentBoundedQueue {

    private final Object[] elements;
    private int count;
    private int head;
    private int tail;
    private final int capacity;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.elements = new Object[capacity];
        this.capacity = capacity;
        this.count = 0;
        this.head = 0;
        this.tail = 0;
    }

    public void put(Object item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot put null item.");
        }

        synchronized (this) {
            while (count == capacity) {
                this.wait();
            }

            elements[tail] = item;
            tail = (tail + 1) % capacity;
            count++;

            this.notifyAll();
        }
    }

    public Object take() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                this.wait();
            }

            Object item = elements[head];
            elements[head] = null; // Clear reference for GC
            head = (head + 1) % capacity;
            count--;

            this.notifyAll();
            return item;
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

    public boolean isFull() {
        synchronized (this) {
            return count == capacity;
        }
    }

    public static void main(String[] args) {
        // Example usage logic can be placed here or in a test class.
    }
}
