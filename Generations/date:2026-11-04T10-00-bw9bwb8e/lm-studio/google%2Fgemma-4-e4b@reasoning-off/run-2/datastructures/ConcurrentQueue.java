@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue {
    @interface ThreadSafe {}


    private final Object[] queueArray;
    private int head;
    private int tail;
    private int count;
    private final int capacity;

    /**
     * Constructs a queue with the specified capacity.
     * @param capacity The maximum number of elements this queue can hold. Must be greater than 0.
     */
    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        // The array size must be sufficient for the capacity and accommodate wrap-around.
        // Using a size equal to capacity is typical, but managing indices requires care.
        // For simplicity in this implementation using head/tail/count, we use capacity + 1 slots
        // to distinguish between a full queue (tail == head) and an empty queue, 
        // though using exactly capacity is possible with careful modulo arithmetic.
        // Let's stick to a size equal to the capacity requested, and use count for tracking.
        this.queueArray = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary until space becomes available.
     * @param element The element to add. Must not be null if the array slot cannot represent null.
     *                  Assuming elements are non-null for simplicity matching typical queue behavior 
     *                  unless null capacity management is explicitly required.
     * @throws InterruptedException if the thread waiting for space is interrupted.
     */
    public void insert(Object element) throws InterruptedException {
        // Wait until there is space available (i.e., count < capacity)
        while (count == capacity) {
            // Current object monitor lock guards against other operations modifying state simultaneously.
            synchronized (this) {
                // If we enter here, count must be equal to capacity. We wait until someone removes an item.
                wait();
            }
        }

        // Acquire lock to modify state (insert into array)
        synchronized (this) {
            queueArray[tail] = element;
            // Advance tail pointer circularly
            tail = (tail + 1) % capacity;
            count++;
            // Notify waiting consumers that an element is now available
            notifyAll();
        }
    }

    /**
     * Removes and returns the head of this queue, waiting if necessary until an element becomes available.
     * @return The head of the queue. Must not be null if successful retrieval is expected.
     * @throws InterruptedException if the thread waiting for an element is interrupted.
     */
    public Object remove() throws InterruptedException {
        // Wait until there is an element available (i.e., count > 0)
        while (count == 0) {
            synchronized (this) {
                // If we enter here, count must be zero. We wait until someone inserts an item.
                wait();
            }
        }

        // Acquire lock to modify state (remove from array)
        synchronized (this) {
            Object element = queueArray[head];
            // Clear the slot for garbage collection aids (optional but good practice)
            queueArray[head] = null; 
            // Advance head pointer circularly
            head = (head + 1) % capacity;
            count--;
            // Notify waiting producers that space is now available
            notifyAll();
            return element;
        }
    }

    /**
     * Tries to insert the specified element into this queue if it is immediately available. 
     * Does not block.
     * @param element The element to add.
     * @return true if the element was added successfully, false if the queue was full.
     */
    public boolean tryInsert(Object element) {
        synchronized (this) {
            if (count < capacity) {
                queueArray[tail] = element;
                tail = (tail + 1) % capacity;
                count++;
                return true;
            } else {
                // Queue is full
                return false;
            }
        }
    }

    /**
     * Tries to remove and return the head of this queue immediately if an element is present.
     * Does not block.
     * @return The head of the queue, or null if the queue was empty.
         */
    public Object tryRemove() {
        synchronized (this) {
            if (count > 0) {
                Object element = queueArray[head];
                queueArray[head] = null; // Help GC
                head = (head + 1) % capacity;
                count--;
                return element;
            } else {
                // Queue is empty
                return null;
            }
        }
    }

    // Note: Destructor/close operations are omitted as per typical usage context, 
    // but in a real system, management of resources would be necessary.
}
