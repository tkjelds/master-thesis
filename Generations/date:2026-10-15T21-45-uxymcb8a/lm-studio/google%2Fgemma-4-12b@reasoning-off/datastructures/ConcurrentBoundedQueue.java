/**
 * A thread-safe Bounded Buffer collection.
 * This implementation provides a producer-consumer queue that limits 
 * the number of items in the collection to prevent memory overflow.
 */
@ConcurrentBoundedQueue.ThreadSafe
public class ConcurrentBoundedQueue {
    @interface ThreadSafe {}


    private final Object[] items;
    private int putPointer;
    private int takePointer;
    private int count;

    private final int capacity;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.putPointer = 0;
        this.takePointer = 0;
        this.count = 0;
    }

    /**
     * Inserts an item into the queue. 
     * Blocks if the buffer is full until space becomes available.
     */
    public void put(Object item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot insert null");
        }

        synchronized (this) {
            while (count == capacity) {
                this.wait();
            }

            items[putPointer] = item;
            putPointer = (putPointer + 1) % capacity;
            count++;

            this.notifyAll();
        }
    }

    /**
     * Removes and returns an item from the queue.
     * Blocks if the buffer is empty until an item is added.
     */
    public Object take() throws InterruptedException {
        Object result;

        synchronized (this) {
            while (count == 0) {
                this.wait();
            }

            result = items[takePointer];
            items[takePointer] = null; // Clear reference for GC
            takePointer = (takePointer + 1) % capacity;
            count--;

            this.notifyAll();
            return result;
        }
    }

    /**
     * Returns the current number of items in the queue.
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns the maximum capacity of the queue.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Simple demonstration of the queue.
     */
    public static void main(String[] args) {
        // Main method left blank as per instructions to provide only the implementation.
    }
}
