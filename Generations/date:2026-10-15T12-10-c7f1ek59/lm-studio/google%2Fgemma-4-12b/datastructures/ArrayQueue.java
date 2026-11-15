@ArrayQueue.ThreadSafe
public class ArrayQueue<T> {
    @interface ThreadSafe {}

    private final Object[] items;
    private int putIndex = 0;
    private int takeIndex = 0;
    private int count = 0;
    private final int capacity;
    private final Object lock = new Object();

    /**
     * Initializes a new instance of the ArrayQueue.
     * @param capacity The maximum number of elements the queue can hold.
     */
    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new RuntimeException("Capacity must be greater than zero.");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    /**
     * Inserts an item into the queue. Blocks if the queue is full.
     * @param item The item to add.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void enqueue(T item) throws InterruptedException {
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }

            items[putIndex] = item;
            putIndex = (putIndex + 1) % capacity;
            count++;

            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the head of the queue. Blocks if the queue is empty.
     * @return The removed item.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @SuppressWarnings("unchecked")
    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }

            T item = (T) items[takeIndex];
            items[takeIndex] = null; // Clear reference for garbage collection
            takeIndex = (takeIndex + 1) % capacity;
            count--;

            lock.notifyAll();
            return item;
        }
    }

    /**
     * Returns the current number of elements in the queue.
     */
    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    /**
     * Checks if the queue is empty.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    /**
     * Checks if the queue is full.
     */
    public boolean isFull() {
        synchronized (lock) {
            return count == capacity;
        }
    }
}
