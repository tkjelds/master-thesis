@ArrayQueue.ThreadSafe
class ArrayQueue {
    @interface ThreadSafe {}


    private int[] queue;
    private int front;
    private int tail;
    private int size;
    private final int capacity;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        // The underlying array needs space for N elements, but we use capacity+1 slots
        // to distinguish between full and empty states using tail == head or similar logic.
        // Since we are implementing a bounded buffer, let's use capacity N+1 array for N items.
        this.capacity = capacity + 1;
        this.queue = new int[this.capacity];
        this.front = 0;
        this.tail = 0;
        this.size = 0;
    }

    /**
     * Inserts the element into the queue, waiting if the queue is full.
     * @param item The element to add.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public void insert(int item) throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is full
            while (size == queue.length - 1) {
                wait();
            }

            // Insert item
            queue[tail] = item;
            tail = (tail + 1) % queue.length;
            size++;

            // Notify waiting consumers that an item is available
            notifyAll();
        }
    }

    /**
     * Removes and returns an element from the queue, waiting if the queue is empty.
     * @return The element dequeued.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public int remove() throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is empty
            while (size == 0) {
                wait();
            }

            // Retrieve item
            int item = queue[front];
            front = (front + 1) % queue.length;
            size--;

            // Notify waiting producers that space is available
            notifyAll();
            return item;
        }
    }

    /**
     * Inserts the element into the queue without blocking if full.
     * @param item The element to add.
     * @return true if the element was added successfully, false if the queue was full.
     */
    public boolean insertNonBlocking(int item) {
        synchronized (this) {
            if (size == queue.length - 1) {
                return false; // Queue is full
            }

            // Insert item
            queue[tail] = item;
            tail = (tail + 1) % queue.length;
            size++;
            return true;
        }
    }

    /**
     * Removes and returns an element from the queue without blocking if empty.
     * @return An array containing the element and a boolean indicating success (true) or failure (false).
     *         If unsuccessful, the array's second element will be false.
     */
    public Object[] removeNonBlocking() {
        synchronized (this) {
            if (size == 0) {
                return new Object[]{null, false}; // Queue is empty
            }

            // Retrieve item
            int item = queue[front];
            front = (front + 1) % queue.length;
            size--;

            return new Object[]{item, true}; // Success
        }
    }

    public int getSize() {
        synchronized (this) {
            return size;
        }
    }
}
