@CustomBoundedBuffer.ThreadSafe
class CustomBoundedBuffer {
    @interface ThreadSafe {}


    private Object[] buffer;
    private int capacity;
    private int head = 0; // Index to insert (producer writes here)
    private int tail = 0; // Index to remove (consumer reads from here)
    private int count = 0;

    /**
     * Constructs a bounded buffer with the specified capacity.
     * @param capacity The maximum number of elements the buffer can hold.
     */
    public CustomBoundedBuffer(int capacity) {
        this.capacity = capacity;
        // The buffer size must be positive and typically a power of 2 for efficient circular array implementation,
        // but here we use simple modulo arithmetic compatible with any positive capacity.
        this.buffer = new Object[capacity];
    }

    /**
     * Inserts the specified element into this buffer, waiting if it is full.
     * @param item The element to add.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public void put(Object item) throws InterruptedException {
        synchronized (this) {
            // Wait while the buffer is full
            while (count == capacity) {
                wait();
            }

            // Insert the item at the current head position (producer index)
            buffer[head] = item;
            // Move head pointer circularly
            head = (head + 1) % capacity;
            count++;

            // Notify waiting consumers that an item is available
            notifyAll();
        }
    }

    /**
     * Removes and returns an object from the buffer, waiting if it is empty.
     * @return The element removed from the buffer.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public Object take() throws InterruptedException {
        synchronized (this) {
            // Wait while the buffer is empty
            while (count == 0) {
                wait();
            }

            // Retrieve the item from the current tail position (consumer reads from here)
            Object item = buffer[tail];
            // Optional: help garbage collection by setting the reference to null
            buffer[tail] = null; 

            // Move tail pointer circularly
            tail = (tail + 1) % capacity;
            count--;

            // Notify waiting producers that space is available
            notifyAll();
            return item;
        }
    }
}
