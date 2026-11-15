@SimpleBoundedQueue.ThreadSafe
class SimpleBoundedQueue implements Runnable {
    @interface ThreadSafe {}


    private int[] queue;
    private int head;
    private int tail;
    private int size;
    private final int capacity;

    public SimpleBoundedQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new int[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    // Producer method: inserts an item into the queue, waiting if full.
    public void put(int item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                wait(); // Wait until signaled that there is space
            }

            // Add item to the tail
            queue[tail] = item;
            tail = (tail + 1) % capacity;
            size++;

            // Notify waiting consumers that an item is available
            notifyAll();
        }
    }

    // Consumer method: removes and returns the oldest item, waiting if empty.
    public int take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait(); // Wait until signaled that there is an item
            }

            // Retrieve item from the head
            int item = queue[head];
            head = (head + 1) % capacity;
            size--;

            // Notify waiting producers that there is space now
            notifyAll();
            return item;
        }
    }

    // Implementation of Runnable interface for testing/simulation purposes (not strictly required by the core task but helpful for a self-contained runnable example)
    @Override
    public void run() {
        // This method is usually where a thread would execute the queue operations.
    }

    public static void main(String[] args) {
        // Example usage demonstration (optional, but useful for verification of correctness)

        int capacity = 5;
        SimpleBoundedQueue queue = new SimpleBoundedQueue(capacity);

        // Example producer thread task
        Runnable producerTask = () -> {
            try {
                for (int i = 1; i <= 10; ++i) {
                    System.out.println("Producing: " + i);
                    queue.put(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Example consumer thread task
        Runnable consumerTask = () -> {
            try {
                for (int i = 0; i < 10; ++i) {
                    int item = queue.take();
                    System.out.println("Consumed: " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Start threads
        Thread producerThread = new Thread(producerTask, "Producer");
        Thread consumerThread = new Thread(consumerTask, "Consumer");

        producerThread.start();
        consumerThread.start();

        // Wait for threads to finish (to keep main thread alive long enough)
        try {
            producerThread.join();
            consumerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
