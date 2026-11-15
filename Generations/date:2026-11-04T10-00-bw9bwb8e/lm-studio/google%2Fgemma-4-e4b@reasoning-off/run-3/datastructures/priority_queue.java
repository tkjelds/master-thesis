@PriorityQueue.ThreadSafe
class PriorityQueue {
    @interface ThreadSafe {}


    private int[] elements;
    private int maxSize;
    private int currentSize;

    public PriorityQueue(int capacity) {
        this.maxSize = capacity;
        // Arrays are 0-indexed, so we need capacity + 1 if we want to use tree structure easily,
        // but for simplicity with array-based heap implementation, we use capacity slots (1 to capacity).
        this.elements = new int[capacity + 1]; // Index 0 unused for 1-based heap indexing
        this.currentSize = 0;
    }

    // Helper methods for Binary Heap operations (assuming Min-Heap behavior unless specified otherwise,
    // but usually PriorityQueue implies retrieving the 'highest priority' element. Here we implement Max-Heap behavior
    // assuming higher integer value means higher priority.)

    private void heapifyUp(int index) {
        while (index > 1 && elements[(index - 1) / 2] < elements[index]) {
            swap(index, (index - 1) / 2);
            index = (index - 1) / 2;
        }
    }

    private void heapifyDown(int index) {
        while (true) {
            int leftChild = 2 * index;
            int rightChild = 2 * index + 1;
            int largest = index;

            if (leftChild <= currentSize && elements[leftChild] > elements[largest]) {
                largest = leftChild;
            }
            if (rightChild <= currentSize && elements[rightChild] > elements[largest]) {
                largest = rightChild;
            }

            if (largest != index) {
                swap(index, largest);
                index = largest;
            } else {
                break;
            }
        }
    }

    private void swap(int i, int j) {
        int temp = elements[i];
        elements[i] = elements[j];
        elements[j] = temp;
    }

    /**
     * Inserts the element into the queue. Blocks if the queue is full until space becomes available.
     * @param item The element to insert.
     */
    public synchronized void insert(int item) throws InterruptedException {
        while (true) {
            if (currentSize >= maxSize) {
                // Queue is full, wait for removal signal
                this.wait();
            } else {
                currentSize++;
                elements[currentSize] = item;
                heapifyUp(currentSize);
                return; // Insertion successful
            }
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary until an element is available.
     * @return The element with the highest priority.
     */
    public synchronized int extractMax() throws InterruptedException {
        while (currentSize == 0) {
            // Queue is empty, wait for insertion signal
            this.wait();
        }

        // Extract root (Max element)
        int maxItem = elements[1];
        
        // Replace root with the last element and shrink heap
        elements[1] = elements[currentSize];
        currentSize--;

        if (currentSize > 0) {
            heapifyDown(1);
        }

        // Signal waiting threads (potential inserts) that space is now available
        this.notifyAll();
        return maxItem;
    }

    /**
     * Returns the current number of elements in this queue. This operation is non-blocking and thread-safe.
     * @return The current size of the queue.
     */
    public synchronized int size() {
        return currentSize;
    }

    /**
     * Checks if the queue is empty. This operation is non-blocking and thread-safe.
     * @return true if the queue is empty, false otherwise.
     */
    public synchronized boolean isEmpty() {
        return currentSize == 0;
    }
}
