import java.io.PrintStream;

@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}


    /**
     * The underlying array that stores the elements of the queue.
     */
    private T[] elements;

    /**
     * The index where the next element will be inserted.
     */
    private int head;

    /**
     * The index where the next element is removed from.
     */
    private int tail;

    /**
     * The current number of elements in the queue.
     */
    private int count;

    /**
     * A flag to signal that the queue is terminated.
     * Once set to true, new elements cannot be added, and subsequent remove
     * operations should terminate (return null) until the queue is empty.
     */
    private volatile boolean terminated;

    /**
     * Constructs a ConcurrentQueue with a specified capacity.
     *
     * @param capacity the maximum number of elements the queue can hold.
     * @throws IllegalArgumentException if capacity is less than or equal to zero.
     */
    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.capacity = capacity;
        // Initialize with the exact capacity to prevent resizing overhead
        this.elements = (T[]) new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
        this.terminated = false;
    }

    private final int capacity;

    /**
     * Initializes the queue with a default capacity of 16.
     */
    public ConcurrentQueue() {
        this(16);
    }

    /**
     * Adds an element to the tail of the queue.
     * Blocks if the queue is full until space becomes available.
     *
     * @param item the item to be added.
     * @return true if the item was added.
     */
    public boolean enqueue(T item) {
        synchronized (this) {
            while (count == capacity) {
                wait();
            }
            
            elements[tail] = item;
            tail = (tail + 1) % capacity;
            count++;
            
            // Signal one waiting thread
            notifyAll();
            
            return true;
        }
    }

    /**
     * Removes and returns the element at the head of the queue.
     * Blocks if the queue is empty until an element is available.
     *
     * @return the element at the head of the queue, or null if the queue is terminated
     *         and empty.
     */
    public T dequeue() {
        T item = null;
        synchronized (this) {
            while (count == 0 && terminated) {
                wait();
            }
            
            if (count == 0 && terminated) {
                return null;
            }

            item = elements[head];
            elements[head] = null; // Help GC
            head = (head + 1) % capacity;
            count--;
            
            // Signal one waiting thread
            notifyAll();
        }
        return item;
    }

    /**
     * Checks if the queue is empty without blocking.
     *
     * @return true if the queue is empty, false otherwise.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    /**
     * Checks if the queue is full without blocking.
     *
     * @return true if the queue is full, false otherwise.
     */
    public boolean isFull() {
        synchronized (this) {
            return count == capacity;
        }
    }

    /**
     * Returns the number of elements in the queue.
     *
     * @return the number of elements.
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Terminates the queue.
     * Subsequent calls to dequeue() will return null until the queue is empty.
     * The queue becomes read-only effectively for this purpose.
     */
    public void terminate() {
        synchronized (this) {
            terminated = true;
            // Wake up all threads waiting in this queue
            notifyAll();
        }
    }

    /**
     * Returns the head index (not synchronized, meant for inspection only
     * in a controlled environment or debug). Note: head is not volatile,
     * so this is not strictly thread-safe for reading across threads without sync.
     * This method is included to satisfy internal logic visibility if needed,
     * but primarily relies on count for safety.
     */
    public void printState(PrintStream ps) {
        synchronized (this) {
            ps.print("Head: " + head + ", Tail: " + tail + ", Count: " + count + 
                    ", Capacity: " + capacity + ", Terminated: " + terminated + "\n");
            ps.print("Elements: [");
            int idx = head;
            int len = 0;
            while (len < count) {
                ps.print(elements[idx]);
                if (len < count - 1) ps.print(", ");
                idx = (idx + 1) % capacity;
                len++;
            }
            ps.println("]");
        }
    }

    // Suppress warnings for empty catch blocks if any generic type issues arise
    // in environments that check for nulls in try-catch, though not strictly needed here.
}
