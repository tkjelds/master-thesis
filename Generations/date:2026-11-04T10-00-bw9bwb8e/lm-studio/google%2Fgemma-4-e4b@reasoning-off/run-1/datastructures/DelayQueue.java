@DelayQueue.ThreadSafe
public class DelayQueue {
    @interface ThreadSafe {}


    private Object[] queue;
    private int maxSize;
    private int head = 0; // Index of the next element to be retrieved
    private int tail = 0; // Index where the next element will be added
    private int size = 0;

    /**
     * Constructs a DelayQueue with the specified maximum size.
     * @param capacity The maximum number of elements this queue can hold.
     */
    public DelayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.maxSize = capacity;
        // The array needs to be sized appropriately, considering circular buffer wrap-around.
        // We use capacity slots for simplicity in this implementation sketch, 
        // but a real circular buffer often uses one more slot or careful modulo arithmetic.
        // Given the constraints, we use 'capacity' slots and manage indices relative to capacity.
        this.queue = new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary until space becomes available.
     * @param item The element to add.
     * @throws InterruptedException if interrupted while waiting.
     */
    public void insert(Object item) throws InterruptedException {
        // Wait until there is space in the queue. Block if full.
        while (size == maxSize) {
            // Wait on the monitor of this object if the queue is full.
            wait();
        }

        // Critical section: Adding the item
        synchronized (this) {
            queue[tail] = item;
            tail = (tail + 1) % maxSize;
            size++;

            // Notify consumers that an item is available.
            notifyAll(); 
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * @return The head of the queue.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object take() throws InterruptedException {
        // Wait until the queue is not empty. Block if empty.
        while (size == 0) {
            // Wait on the monitor of this object if the queue is empty.
            wait();
        }

        Object item;
        // Critical section: Retrieving the item
        synchronized (this) {
            item = queue[head];
            // Nullify the slot for potential garbage collection, though not strictly necessary 
            // in a perfect bounded buffer simulation where indices manage state.
            queue[head] = null; 
            head = (head + 1) % maxSize;
            size--;

            // Notify producers that space is now available.
            notifyAll();
        }
        return item;
    }

    /**
     * Returns the current number of elements in this queue.
     * Note: This implementation returns size without blocking, achieving a non-blocking read of the state.
     * @return The number of elements currently in the queue.
     */
    public int size() {
        // Reading 'size' must be protected to ensure visibility and atomicity with producers/consumers.
        synchronized (this) {
            return size;
        }
    }

    // --- Example Usage/Testing Skeleton (Not required for the solution, but useful context) ---
    /*
    public static void main(String[] args) throws InterruptedException {
        DelayQueue dq = new DelayQueue(2);

        Runnable producer = () -> {
            try {
                for (int i = 1; ; i++) {
                    System.out.println(Thread.currentThread().getName() + " producing: " + i);
                    dq.insert("Item " + i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable consumer = () -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Object item = dq.take();
                    System.out.println(Thread.currentThread().getName() + " consumed: " + item);
                }
            } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
            }
        };

        Thread t1 = new Thread(producer, "Producer-1");
        Thread t2 = new Thread(consumer, "Consumer-1");

        t1.start();
        t2.start();

        t1.join(); // In a real test, this wouldn't join forever
    }
    */
}
