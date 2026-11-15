@B_Tres.ThreadSafe
public class B_Tres<T> {
    @interface ThreadSafe {}

    private final Object[] items;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    /**
     * Initializes the B_Tres data structure with a fixed capacity.
     * 
     * @param capacity The maximum number of elements the structure can hold.
     */
    public B_Tres(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero.");
        }
        this.items = new Object[capacity];
    }

    /**
     * Inserts an item into the data structure. 
     * If the structure is full, this operation blocks until space becomes available.
     * 
     * @param item The item to add.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    public void put(T item) throws InterruptedException {
        synchronized (this) {
            // Wait while the buffer is full
            while (count == items.length) {
                wait();
            }

            // Add the item
            items[tail] = item;
            tail = (tail + 1) % items.length;
            count++;

            // Notify any threads waiting to take an item
            notifyAll();
        }
    }

    /**
     * Removes and returns an item from the data structure.
     * If the structure is empty, this operation blocks until an item becomes available.
     * 
     * @return The retrieved item.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        synchronized (this) {
            // Wait while the buffer is empty
            while (count == 0) {
                wait();
            }

            // Extract the item
            T item = (T) items[head];
            items[head] = null; // Clear reference for garbage collection
            head = (head + 1) % items.length;
            count--;

            // Notify any threads waiting to put an item
            notifyAll();
            return item;
        }
    }

    /**
     * Returns the number of items currently in the structure.
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns the total capacity of the structure.
     */
    public int getCapacity() {
        return items.length;
    }
}
