@ConcurrentBoundedBuffer.ThreadSafe
class ConcurrentBoundedBuffer implements Runnable {
    @interface ThreadSafe {}


    private int[] buffer;
    private int capacity;
    private int head = 0; // Index to insert (write)
    private int tail = 0; // Index to remove (read)
    private int count = 0; // Current number of elements

    private boolean running = true;
    private Thread thread;

    // Constructor for the buffer (assuming a fixed size)
    public ConcurrentBoundedBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new int[capacity];
    }

    // --- Producer/Consumer Simulation Methods (For demonstration) ---

    /**
     * Inserts an element into the buffer, blocking if full.
     */
    public void produce(int item) throws InterruptedException {
        // The object reference itself acts as the monitor lock in this context.
        synchronized (this) {
            // Wait while the buffer is full and we are still running.
            while (count == capacity && running) {
                wait();
            }

            // If we woke up because production is stopping AND the buffer is full, exit gracefully.
            if (!running && count == capacity) {
                return;
            }

            // Produce item
            buffer[head] = item;
            head = (head + 1) % capacity;
            count++;

            // Signal that an item is available
            notifyAll();
        }
    }

    /**
     * Retrieves an element from the buffer, blocking if empty.
     * @return The retrieved item.
     * @throws InterruptedException If the wait is interrupted while blocked.
     */
    public int consume() throws InterruptedException {
        synchronized (this) {
            // Wait while the buffer is empty and we are still running.
            while (count == 0 && running) {
                wait();
            }

            // If we woke up because production is stopping AND the buffer is empty, exit gracefully.
             if (!running && count == 0) {
                throw new IllegalStateException("Buffer is empty and shutting down.");
            }

            // Consume item
            int item = buffer[tail];
            tail = (tail + 1) % capacity;
            count--;

            // Signal that space is available
            notifyAll();
            return item;
        }
    }

    /**
     * Signals all waiting threads that no more production/consumption will occur.
     */
    public void shutdown() {
        synchronized (this) {
            running = false;
            // Wake up all threads so they can observe the 'running' flag and exit loops.
            notifyAll();
        }
    }

    // --- Testing/Demonstration Main Method (Not part of the core requirement, but useful for completeness) ---

    public static void main(String[] args) throws InterruptedException {
        final int CAPACITY = 5;
        ConcurrentBoundedBuffer buffer = new ConcurrentBoundedBuffer(CAPACITY);

        Runnable producerTask = () -> {
            try {
                for (int i = 0; i < 15; i++) { // Produce more than capacity to test blocking
                    buffer.produce(i + 1);
                    System.out.println("Produced: " + (i + 1));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable consumerTask = () -> {
            try {
                for (int i = 0; i < 15; i++) { // Try to consume all produced items
                    int item = buffer.consume();
                    System.out.println("Consumed: " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException e) {
                System.out.println("Consumer detected shutdown.");
            }
        };

        Thread producer = new Thread(producerTask, "Producer");
        Thread consumer = new Thread(consumerTask, "Consumer");

        producer.start();
        consumer.start();

        // Wait for simulation to run (in a real scenario, management thread handles shutdown)
        producer.join();
        consumer.join();

        buffer.shutdown();
    }
}
