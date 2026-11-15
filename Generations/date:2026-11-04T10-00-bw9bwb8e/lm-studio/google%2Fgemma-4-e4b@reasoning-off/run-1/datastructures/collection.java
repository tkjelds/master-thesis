@ConcurrentBoundedQueue.ThreadSafe
class ConcurrentBoundedQueue {
    @interface ThreadSafe {}


    private Object[] buffer;
    private int head;
    private int tail;
    private int count;
    private final int capacity;

    /**
     * Constructs a bounded queue with the given capacity.
     * @param capacity The maximum number of elements this queue can hold. Must be greater than 0.
     * @throws IllegalArgumentException if capacity is less than or equal to 0.
     */
    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.capacity = capacity;
        // Array size is capacity, but since we are using a circular buffer based on indices
        // and relying on 'count', the physical array might need one extra slot if we used it
        // to distinguish full/empty when count wasn't maintained. Here, capacity is sufficient for the queue size.
        this.buffer = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary until space becomes available.
     * Blocks if the queue is full.
     * @param item The element to add.
     */
    public void put(Object item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null.");
        }

        while (true) {
            synchronized (this) {
                // Wait while the queue is full
                while (count == capacity) {
                    wait(); // Release lock and wait until notified
                }

                // Insert item
                buffer[tail] = item;
                tail = (tail + 1) % capacity;
                count++;

                // Notify consumers that an item is available
                notifyAll();
                return; // Success
            }
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * Blocks if the queue is empty.
     * @return The head of this queue.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object take() throws InterruptedException {
        while (true) {
            synchronized (this) {
                // Wait while the queue is empty
                while (count == 0) {
                    wait(); // Release lock and wait until notified
                }

                // Retrieve item
                Object item = buffer[head];
                buffer[head] = null; // Help garbage collection and indicate removal
                head = (head + 1) % capacity;
                count--;

                // Notify producers that space is available
                notifyAll();
                return item; // Success
            }
        }
    }

    /**
     * Returns the current number of elements in this queue.
     * This operation is thread-safe but may return a value that immediately becomes stale.
     * @return The current number of elements in the queue.
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns true if this queue contains no elements.
     * This operation is thread-safe.
     * @return true if the queue is empty, false otherwise.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }
}
