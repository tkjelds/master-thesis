@MyQueue.ThreadSafe
class MyQueue {
    @interface ThreadSafe {}

    private int[] queueArray;
    private int head;
    private int tail;
    private int size;
    private final int capacity;

    public MyQueue(int capacity) {
        this.capacity = capacity + 1; // Using one extra slot to distinguish between full and empty
        this.queueArray = new int[this.capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    /**
     * Inserts the element into the queue. Blocks if the queue is full until space becomes available.
     * @param item The element to add.
     */
    public void insert(int item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity - 1) { // Queue is full when size equals capacity - 1 due to implementation detail
                wait(); // Wait if the queue is full
            }

            queueArray[tail] = item;
            tail = (tail + 1) % capacity;
            size++;

            notifyAll(); // Notify waiting threads (consumers) that an item is available
        }
    }

    /**
     * Removes and returns the element from the queue. Blocks if the queue is empty until an item arrives.
     * @return The element removed from the head of the queue.
     */
    public int remove() throws InterruptedException {
        synchronized (this) {
            while (size == 0) { // Wait if the queue is empty
                wait(); // Wait until an item is available
            }

            int item = queueArray[head];
            head = (head + 1) % capacity;
            size--;

            notifyAll(); // Notify waiting threads (producers) that space is available
            return item;
        }
    }

    /**
     * Inserts the element into the queue, returning immediately if successful. Does not block.
     * @param item The element to add.
     * @return true if the operation succeeded, false if the queue was full.
     */
    public boolean offer(int item) {
        synchronized (this) {
            if (size == capacity - 1) { // Check if full
                return false;
            }

            queueArray[tail] = item;
            tail = (tail + 1) % capacity;
            size++;
            return true;
        }
    }

    /**
     * Removes and returns the element from the queue, returning immediately if empty. Does not block.
     * @return An array containing {value, success_flag}. If success_flag is false, the queue was empty.
     *         The value might be arbitrary if success_flag is false.
     */
    public int[] poll() {
        synchronized (this) {
            if (size == 0) { // Check if empty
                return new int[]{0, 0}; // Sentinel indicating failure/empty
            }

            int item = queueArray[head];
            head = (head + 1) % capacity;
            size--;

            return new int[]{item, 1}; // Sentinel indicating success and including the value
        }
    }
}
