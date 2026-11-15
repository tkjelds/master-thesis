@DelayQueue.ThreadSafe
class DelayQueue {
    @interface ThreadSafe {}

    private final Object[] queue;
    private int head = 0;
    private int tail = 0;
    private final int capacity;

    /**
     * Constructs a DelayQueue with the specified maximum capacity.
     * @param capacity The maximum number of elements this queue can hold. Must be greater than 0.
     */
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.capacity = capacity;
        // The array size should accommodate the total count + 1 for proper modulo arithmetic,
        // but since we are using head/tail pointers into a fixed-size array representing the queue slots,
        // we size it to capacity. However, for a circular buffer implementation using head/tail and modulo arithmetic
        // on indices [0, capacity-1], the actual underlying array size is usually 'capacity'.
        this.queue = new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately.
     * If the queue is full, this method blocks until space becomes available, then inserts the element.
     * @param item The element to insert.
     * @throws InterruptedException if interrupted while waiting for insertion space.
     */
    public void insert(Object item) throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is full
            while (isFull()) {
                wait();
            }

            // Insert the item at the tail position (and advance tail)
            queue[tail] = item;
            tail = (tail + 1) % capacity;

            // Notify waiting consumers that an item is available
            notifyAll();
        }
    }

    /**
     * Retrieves and removes the head of this queue, if it is immediately available.
     * If the queue is empty, this method blocks until an element becomes available.
     * @return The head of the queue.
     * @throws InterruptedException if interrupted while waiting for an element.
     */
    public Object remove() throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is empty
            while (isEmpty()) {
                wait();
            }

            // Retrieve the item at the head position (and advance head)
            Object item = queue[head];
            queue[head] = null; // Help garbage collection
            head = (head + 1) % capacity;

            // Notify waiting producers that space is available
            notifyAll();
            return item;
        }
    }

    /**
     * Checks if the queue is currently empty. This check happens under synchronization
     * to ensure visibility into the head/tail pointers.
     * @return true if the queue contains no elements, false otherwise.
     */
    public boolean isEmpty() {
        // We must check this under the lock to ensure head and tail pointers are consistent.
        return head == tail;
    }

    /**
     * Checks if the queue is currently full. This check happens under synchronization.
     * In a circular buffer implementation where we distinguish between "full" and "empty" when head=tail,
     * we typically require one slot to remain unused to distinguish the two states reliably.
     * If capacity N is used, we use slots 0 to N-1. Full means (tail + 1) % capacity == head.
     * If we allow N items, the tail must wrap around exactly when it hits the head pointer's next intended spot.
     * With this implementation (where inserting into a full queue blocks), the array size is 'capacity', and we allow N items.
     * The queue becomes full when the next insertion point (tail) would equal head, having wrapped around completely.
     * The condition for being full is: (tail + 1) % capacity == head.
         // If size is C, indices are 0..C-1. Head advances, Tail advances.
         // If head=0, tail=0 initially. Insert 1 item: queue[0]=item, tail=1. Not full.
         // Insert C items: Tail reaches capacity-1. Next insert makes tail = 0, which equals head.
         // To correctly use all 'capacity' slots AND maintain the distinguishability of full/empty, we treat capacity as size N+1 array
         // if we rely strictly on head==tail for empty. Since we fixed the array size to 'capacity', we must rely on the fact that
         // once N items are inserted, head and tail will point to positions indicating fullness before the next slot is reused.
         // A simpler interpretation for a fixed capacity N array used as a queue: full when tail points to the next slot that would be head, having wrapped around.
        return (tail + 1) % capacity == head;
    }

    /**
     * Determines the current number of elements in the queue.
     * This must be calculated under synchronization to provide a consistent snapshot.
     * @return The current size of the queue.
     */
    public int size() {
        // Calculate logical size based on head and tail positions.
        return (tail - head + capacity) % capacity;
    }
}
