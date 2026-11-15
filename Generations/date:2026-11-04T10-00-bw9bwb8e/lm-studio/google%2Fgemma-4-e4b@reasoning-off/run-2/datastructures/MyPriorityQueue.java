@MyPriorityQueue.ThreadSafe
public class MyPriorityQueue {
    @interface ThreadSafe {}


    private int[] data;
    private int maxSize;
    private int currentSize;

    /**
     * Constructs a bounded priority queue with the specified maximum size.
     * The implementation uses an array-backed binary heap structure, although this
     * internal detail is hidden from the public interface.
     */
    public MyPriorityQueue(int maxSize) {
        this.maxSize = maxSize;
        // Array size needs to be slightly larger or implementation must handle array bounds checks carefully.
        // For simplicity matching common heap implementations, we use maxSize+1 and ignore index 0.
        this.data = new int[maxSize + 1];
        this.currentSize = 0;
    }

    /**
     * Inserts the specified element into this queue if it is not full.
     * If the queue is full, this method blocks until space becomes available, then inserts the element.
     * The comparison assumes standard integer comparison (higher number has higher priority).
     * @param element The element to insert.
     * @throws InterruptedException if the thread is interrupted while waiting for space.
     */
    public void insert(int element) throws InterruptedException {
        // Wait while the queue is full and no space becomes available.
        while (currentSize == maxSize) {
            synchronized (this) {
                // Wait only if we are full.
                while (currentSize == maxSize) {
                    this.wait();
                }
                // Once notified and passed the guard while loop, there is space.
            }

            // Critical section for insertion: must be inside synchronized block
            synchronized (this) {
                if (currentSize < maxSize) {
                    // Insert the element into the heap structure
                    currentSize++;
                    bubbleUp(currentSize, element);
                    // Signal that an item has been added (in case someone is waiting for removal)
                    this.notifyAll();
                } else {
                    // This path should ideally not be reached if waiting worked correctly, 
                    // but defensively handles spurious wakeups or race conditions if logic above was flawed.
                }
            } // End of synchronized block after waiting/inserting

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e; // Re-throwing as per original method signature requirement if necessary, though the problem implies blocking until success.
        }

        // --- Refactoring to simplify waiting/insertion loop structure ---
        // The original complex structure above is error-prone regarding wait/notify usage.
        // Let's restructure to follow standard condition variable pattern rigorously:

        synchronized (this) {
            // Wait while full
            while (currentSize == maxSize) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException(); // Propagate interruption
                }
            }

            // Add element and maintain heap property
            currentSize++;
            bubbleUp(currentSize, element);

            // Notify waiting elements that an item is available
            this.notifyAll();
        }
    }


    /**
     * Retrieves and removes the highest priority element from this queue if it is not empty.
     * If the queue is empty, this method blocks until an element becomes available, then returns it.
     * @return The highest priority element in the queue.
     * @throws InterruptedException if the thread is interrupted while waiting for an element.
     */
    public int remove() throws InterruptedException {
        synchronized (this) {
            // Wait while empty
            while (currentSize == 0) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException(); // Propagate interruption
                }
            }

            // Remove root (highest priority) element
            int element = data[1];
            data[1] = data[currentSize]; // Move last element to root
            bubbleDown(1);
            currentSize--;

            // Notify waiting inserters that space is available
            this.notifyAll();

            return element;
        }
    }


    // --- Heap Helper Methods (Maintain Max-Heap property on indices) ---

    private void bubbleUp(int k, int elementToAdd) {
        // If we are adding a new element (k == currentSize), we place it at data[k]
        if (k > 1) {
            int current = k;
            data[current] = elementToAdd; // Temporarily set the value to be bubbleable up
            int parent = current / 2;

            // Restore the element value after checks, or handle placement if using a standard insert flow.
            // Assuming `bubbleUp` is called immediately after insertion increments size and places the new value at data[currentSize]
            // If we modify `insert` to pass the new element only:
            if (k > 1 && data[current] < data[parent]) {
                // Swap needed for bubble up to restore heap property
                int temp = data[current];
                data[current] = data[parent];
                data[parent] = temp;
            }
        }
    }

    // Since the insert flow was simplified above to use the final correct structure, 
    // we must ensure these helpers work correctly with the in-place maintenance.

    private void siftUp(int index) {
        // Element at 'index' needs to move up
        while (index > 1 && data[index] > data[index / 2]) {
            int temp = data[index];
            data[index] = data[index / 2];
            data[index / 2] = temp;
            index /= 2;
        }
    }

    private void siftDown(int index) {
        // Element at 'index' needs to move down
        while (true) {
            int left = 2 * index;
            int right = 2 * index + 1;
            int largest = index;

            if (left <= currentSize && data[left] > data[largest]) {
                largest = left;
            }
            if (right <= currentSize && data[right] > data[largest]) {
                largest = right;
            }

            if (largest != index) {
                // Swap and continue sifting down from the swapped position
                int temp = data[index];
                data[index] = data[largest];
                data[largest] = temp;
                index = largest;
            } else {
                break; // Heap property restored at this node
            }
        }
    }

    // --- Finalized, corrected implementation using proper private methods integrated with the main loops ---

    public void insert_final(int element) throws InterruptedException {
        synchronized (this) {
            // Wait while full
            while (currentSize == maxSize) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException();
                }
            }

            // Add element and maintain heap property
            currentSize++;
            data[currentSize] = element; // Add to the next available spot (bottom of heap)
            siftUp(currentSize);

            // Notify waiting removers that an item is available
            this.notifyAll();
        }
    }

    public int remove_final() throws InterruptedException {
        synchronized (this) {
            // Wait while empty
            while (currentSize == 0) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException();
                }
            }

            // Remove root (highest priority) element
            int element = data[1];

            if (currentSize == 0) { // Should not happen due to while loop guard
                throw new IllegalStateException("Queue unexpectedly became empty.");
            }

            // Replace root with the last element and sift down
            data[1] = data[currentSize];
            siftDown(1);
            currentSize--;

            // Notify waiting inserters that space is available
            this.notifyAll();

            return element;
        }
    }


    // --- Adapting the class to use the final working logic under the original method names ---

    public void insert(int element) throws InterruptedException {
        insert_final(element);
    }

    public int remove() throws InterruptedException {
        return remove_final();
    }


    // Clean up the class structure by removing duplicative methods and using the final working ones
}
// Note: The provided solution below merges all these successful parts into a single, cohesive class structure.

// ================= START OF FINAL SUBMISSION CODE BLOCK ===============
// The following code block represents the entirety of the required submission.

@MyPriorityQueue.ThreadSafe
public class MyPriorityQueue {
    @interface ThreadSafe {}


    private int[] data;
    private int maxSize;
    private int currentSize;

    /**
     * Constructs a bounded priority queue with the specified maximum size.
     */
    public MyPriorityQueue(int maxSize) {
        this.maxSize = maxSize;
        // Use array size matching the requirement (1-based indexing for heap)
        this.data = new int[maxSize + 1];
        this.currentSize = 0;
    }

    /**
     * Inserts the specified element into this queue if it is not full.
     * If the queue is full, this method blocks until space becomes available, then inserts the element.
     * Blocks on addition when full.
     * @param element The element to insert.
     * @throws InterruptedException if the thread is interrupted while waiting for space.
     */
    public void insert(int element) throws InterruptedException {
        synchronized (this) {
            // Wait while full (Condition Guard)
            while (currentSize == maxSize) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    // Restore interruption status and propagate
                    Thread.currentThread().interrupt();
                    throw e; 
                }
            }

            // Insert element and maintain heap property (Critical Section)
            currentSize++;
            data[currentSize] = element; // Place new item at the bottom
            siftUp(currentSize);

            // Notify waiters (removers) that data is available
            this.notifyAll();
        }
    }

    /**
     * Retrieves and removes the highest priority element from this queue if it is not empty.
     * If the queue is empty, this method blocks until an element becomes available, then returns it.
     * Blocks on removal when empty.
     * @return The highest priority element in the queue.
     * @throws InterruptedException if the thread is interrupted while waiting for an element.
     */
    public int remove() throws InterruptedException {
        synchronized (this) {
            // Wait while empty (Condition Guard)
            while (currentSize == 0) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    // Restore interruption status and propagate
                    Thread.currentThread().interrupt();
                    throw e; 
                }
            }

            // Remove root element (Critical Section)
            int element = data[1];

            if (currentSize == 0) { 
                // Should be impossible due to the while guard, but defensive programming is sound.
                throw new IllegalStateException("Queue became empty unexpectedly.");
            }

            // Replace root with the last element and restore heap property
            data[1] = data[currentSize];
            siftDown(1);
            currentSize--;

            // Notify waiters (inserters) that space is available
            this.notifyAll();

            return element;
        }
    }

    // --- Heap Maintenance Methods (Operate on the internal array `data` relative to `currentSize`) ---

    /**
     * Moves an element up the heap structure until heap property is satisfied. (Max-Heap)
     * Assumes index `k` is valid and currentSize >= k.
     */
    private void siftUp(int k) {
        // Compare element at k with its parent (k/2)
        while (k > 1 && data[k] > data[k / 2]) {
            // Swap operation between current node k and its parent
            int temp = data[k];
            data[k] = data[k / 2];
            data[k / 2] = temp;
            k /= 2; // Move up to parent's previous position
        }
    }

    /**
     * Moves an element down the heap structure until heap property is satisfied. (Max-Heap)
     * Assumes index `k` is valid and currentSize >= k.
     */
    private void siftDown(int k) {
        while (true) {
            int left = 2 * k;
            int right = 2 * k + 1;
            int largest = k;

            // Compare with children to find the largest among k, left, and right
            if (left <= currentSize && data[left] > data[largest]) {
                largest = left;
            }
            if (right <= currentSize && data[right] > data[largest]) {
                largest = right;
            }

            // If the current node is not the largest, swap and continue sifting down
            if (largest != k) {
                // Swap operation
                int temp = data[k];
                data[k] = data[largest];
                data[largest] = temp;
                // Move down from the child's previous position
                k = largest; 
            } else {
                break; // Heap property restored at this node
            }
        }
    }
}
