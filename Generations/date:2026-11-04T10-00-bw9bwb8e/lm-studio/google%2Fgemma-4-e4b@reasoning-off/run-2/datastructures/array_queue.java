@ArrayQueue.ThreadSafe
class ArrayQueue {
    @interface ThreadSafe {}


    private int[] items;
    private int head;
    private int tail;
    private int size;
    private final int capacity;

    /**
     * Constructs an ArrayQueue with the given initial capacity.
     * The queue is initialized to be empty.
     */
    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new int[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately
     * rather than blocking. Returns true if successful, false if the queue is full.
     *
     * @param element The element to add.
     * @return true if the element was added, false otherwise (queue is full).
     */
    public boolean insert(int element) {
        // Synchronization block using 'this' object monitor
        synchronized (this) {
            if (size == capacity) {
                return false; // Queue is full
            }

            items[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            return true;
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary until space becomes available.
     * @param element The element to add.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public void insertAndWait(int element) throws InterruptedException {
        // Synchronization block using 'this' object monitor
        synchronized (this) {
            // Wait while the queue is full
            while (size == capacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Restore interrupt status and propagate exception
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }

            // Insert the element
            items[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
        } // Exit synchronized block implicitly when monitor is released
    }

    /**
     * Retrieves and removes the head of this queue if it is present. If the queue is empty,
     * this method blocks until an element becomes available.
     *
     * @return The head of the queue.
     * @throws InterruptedException if interrupted while waiting for an element.
     */
    public int removeAndWait() throws InterruptedException {
        // Synchronization block using 'this' object monitor
        synchronized (this) {
            // Wait while the queue is empty
            while (size == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Restore interrupt status and propagate exception
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }

            // Remove the element
            int element = items[head];
            head = (head + 1) % capacity;
            size--;
            return element;
        } // Exit synchronized block implicitly when monitor is released
    }

    /**
     * Retrieves and removes the head of this queue if it is present, otherwise returns null.
     * This operation does not block.
     *
     * @return The head of the queue, or null if the queue is empty.
     */
    public Integer removeIfPresent() {
        // Synchronization block using 'this' object monitor
        synchronized (this) {
            if (size == 0) {
                return null; // Queue is empty
            }

            int element = items[head];
            head = (head + 1) % capacity;
            size--;
            return element;
        } // Exit synchronized block implicitly when monitor is released
    }

    /**
     * Returns the current number of elements in this queue.
     * This operation is thread-safe as it accesses the shared 'size' variable under synchronization.
     *
     * @return The current number of elements in the queue.
     */
    public int size() {
        // Synchronization block using 'this' object monitor
        synchronized (this) {
            return size;
        } // Exit synchronized block implicitly when monitor is released
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately rather than blocking.
     * Returns true if successful, false if the queue is full. (Alias for insert)
     * @param element The element to add.
     * @return true if added, false if full.
     */
    public boolean offer(int element) {
        return insert(element);
    }

    /**
     * Attempts to remove the head of this queue. If the queue is empty, returns null immediately.
     * (Alias for removeIfPresent)
     * @return The head element, or null if empty.
     */
    public Integer poll() {
        return removeIfPresent();
    }
}
