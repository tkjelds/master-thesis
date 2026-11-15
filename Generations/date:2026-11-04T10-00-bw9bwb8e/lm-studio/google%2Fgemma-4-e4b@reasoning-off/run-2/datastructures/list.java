@ConcurrentList.ThreadSafe
class ConcurrentList {
    @interface ThreadSafe {}


    private Object[] listArray;
    private int size;
    private final int capacity;

    public ConcurrentList(int capacity) {
        this.capacity = capacity;
        this.listArray = new Object[capacity];
        this.size = 0;
    }

    // Adds an element to the end of the list. Blocks if the list is full.
    public synchronized void add(Object element) throws InterruptedException {
        while (size == capacity) {
            // If the list is full, wait until notified that space is available.
            // The 'this' object is used as the monitor lock for blocking operations.
            wait();
        }

        // Critical section: adding the element
        listArray[size] = element;
        size++;

        // Notify waiting threads (e.g., those calling remove or take) that the list is no longer empty
        notifyAll();
    }

    // Removes and returns the element from the beginning of the list. Blocks if the list is empty.
    public synchronized Object remove() throws InterruptedException {
        while (size == 0) {
            // If the list is empty, wait until notified that an element has been added.
            wait();
        }

        // Critical section: removing the element
        Object element = listArray[0];
        // Shift elements to maintain contiguity (simulating a queue/list behavior in an array)
        for (int i = 0; i < size - 1; i++) {
            listArray[i] = listArray[i + 1];
        }
        // Clear the last slot (optional, but good practice)
        listArray[size - 1] = null;
        size--;

        // Notify waiting threads (e.g., those calling add) that space is now available
        notifyAll();
        return element;
    }

    // Returns the current size of the list. This operation does not block and reads volatile state implicitly
    // because it's protected by the monitor lock when using 'synchronized'. However, since we aim for simple retrieval
    // without blocking on add/remove, we can make this operation non-blocking but still atomic with respect to the size variable.
    // To strictly adhere to synchronization rules while keeping it fast, we make it synchronized block around reads/writes of 'size'.
    public int getSize() {
        synchronized (this) {
            return size;
        }
    }
}
