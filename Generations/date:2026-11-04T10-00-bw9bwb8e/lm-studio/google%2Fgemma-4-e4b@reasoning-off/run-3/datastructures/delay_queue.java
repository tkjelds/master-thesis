@DelayQueue.ThreadSafe
class DelayQueue {
    @interface ThreadSafe {}

    private Object[] queue;
    private int head = 0;
    private int tail = 0;
    private final int capacity;

    public DelayQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new Object[capacity];
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary until space becomes available.
     * Note: In a real-world scenario, 'element' would likely be something that determines the delay.
     * For this simulation of a bounded blocking queue, we treat insertion as taking one unit of time
     * and successful enqueueing as available space.
     * Since the problem description is minimal on how 'delay' works without external timing mechanisms,
     * we implement a bounded blocking queue where insertion blocks if full.
     */
    public void insert(Object element) throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is full
            while (tail == head + capacity) {
                wait();
            }

            // Insert element
            queue[tail] = element;
            tail++;

            // Notify waiting threads that an item might be available
            notifyAll();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element becomes available.
     * Returns null if the queue is empty (though in a blocking queue scenario, it should wait).
     * Since we are simulating a bounded blocking queue and need to block until an element arrives,
     * this method blocks until an item is present.
     */
    public Object retrieve() throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is empty
            while (head == tail) {
                wait();
            }

            // Retrieve element
            Object element = queue[head];
            queue[head] = null; // Help garbage collection and indicate slot is free
            head++;

            // Notify waiting threads that space might now be available
            notifyAll();

            return element;
        }
    }
}
