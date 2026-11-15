/**
 * A thread-safe implementation of a bounded Buffer (often referred to as b_tres in certain contexts
 * or a Blocking Queue/Buffer) using the Producer-Consumer pattern.
 * 
 * This implementation uses a circular buffer approach with two condition variables (modeled via monitors)
 * to handle overflow and underflow.
 */
@ConcurrentBuffer.ThreadSafe
public class ConcurrentBuffer {
    @interface ThreadSafe {}


    private final Object[] buffer;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    private final int capacity;

    /**
     * @param capacity The maximum number of items the buffer can hold.
     */
    public ConcurrentBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    /**
     * Inserts an item into the buffer. 
     * Blocks if the buffer is full until space becomes available.
     */
    public void put(Object item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot put null item");
        }

        synchronized (this) {
            while (count == capacity) {
                // Wait until there is space available
                this.wait();
            }

            buffer[tail] = item;
            tail = (tail + 1) % capacity;
            count++;

            // Notify any waiting consumers that an item is available
            this.notifyAll();
        }
    }

    /**
     * Removes and returns an item from the buffer.
     * Blocks if the buffer is empty until an item is added.
     */
    public Object take() throws InterruptedException {
        Object item;

        synchronized (this) {
            while (count == 0) {
                // Wait until there is an item available
                this.wait();
            }

            item = buffer[head];
            buffer[head] = null; // Clear reference for GC
            head = (head + 1) % capacity;
            count--;

            // Notify any waiting producers that space is available
            this.notifyAll();
            return item;
        }
    }

    /**
     * Returns the current number of elements in the buffer.
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns true if the buffer is empty.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    /**
     * Returns true if the buffer is full.
     */
    public boolean isFull() {
        synchronized (this) {
            return count == capacity;
        }
    }
}
