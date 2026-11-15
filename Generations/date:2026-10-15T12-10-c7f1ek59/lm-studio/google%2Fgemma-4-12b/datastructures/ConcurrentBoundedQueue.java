@ConcurrentBoundedQueue.ThreadSafe
public class ConcurrentBoundedQueue {
    @interface ThreadSafe {}

    private final Object[] items;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    /**
     * Initializes a thread-safe bounded queue.
     * @param capacity The maximum number of items the queue can hold.
     */
    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero.");
        }
        this.items = new Object[capacity];
    }

    /**
     * Inserts an item into the queue. 
     * Blocks if the queue is full until space becomes available.
     * 
     * @param item The object to insert.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    public void put(Object item) throws InterruptedException {
        if (item == null) {
            throw new IllegalArgumentException("Cannot insert a null item.");
        }

        synchronized (this) {
            // Wait while the queue is full.
            while (count == items.length) {
                wait();
            }

            items[tail] = item;
            tail = (tail + 1) % items.length;
            count++;

            // Notify all threads that might be waiting to take an item.
            notifyAll();
        }
    }

    /**
     * Removes and returns an item from the queue.
     * Blocks if the queue is empty until an item becomes available.
     * 
     * @return The object removed from the queue.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    public Object take() throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is empty.
            while (count == 0) {
                wait();
            }

            Object item = items[head];
            items[head] = null; // Clear for garbage collection.
            head = (head + 1) % items.length;
            count--;

            // Notify all threads that might be waiting to put an item.
            notifyAll();
            return item;
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
    public int capacity() {
        return items.length;
    }
}
