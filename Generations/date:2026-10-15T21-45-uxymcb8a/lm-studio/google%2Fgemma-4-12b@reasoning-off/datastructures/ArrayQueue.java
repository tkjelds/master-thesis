@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}


    private final Object[] elements;
    private int head = 0;
    private int tail = 0;
    private int count = 0;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.elements = new Object[capacity];
    }

    /**
     * Inserts the specified element into the queue. 
     * Blocks if the queue is full until space becomes available.
     */
    public void enqueue(Object item) throws InterruptedException {
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null");
        }

        synchronized (this) {
            while (count == elements.length) {
                wait();
            }

            elements[tail] = item;
            tail = (tail + 1) % elements.length;
            count++;

            notifyAll();
        }
    }

    /**
     * Retrieves and removes the head of the queue.
     * Blocks if the queue is empty until an item becomes available.
     */
    public Object dequeue() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }

            Object item = elements[head];
            elements[head] = null; // Clear reference for GC
            head = (head + 1) % elements.length;
            count--;

            notifyAll();
            return item;
        }
    }

    /**
     * Returns the current number of items in the queue.
     */
    public int size() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns true if the queue is empty.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    /**
     * Returns true if the queue is full.
     */
    public boolean isFull() {
        synchronized (this) {
            return count == elements.length;
        }
    }

    /**
     * Returns the capacity of the queue.
     */
    public int capacity() {
        return elements.length;
    }
}
