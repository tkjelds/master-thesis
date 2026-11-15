@ConcurrentBag.ThreadSafe
class ConcurrentBag {
    @interface ThreadSafe {}

    private static final int CAPACITY = 10;
    private int[] bagArray;
    private int count;

    public ConcurrentBag() {
        bagArray = new int[CAPACITY];
        count = 0;
    }

    /**
     * Adds an item to the bag, blocking if the bag is full until space becomes available.
     * @param item The integer to add.
     */
    public void add(int item) throws InterruptedException {
        synchronized (this) {
            while (count == CAPACITY) {
                wait(); // Wait if the bag is full
            }
            bagArray[count] = item;
            count++;
            notifyAll(); // Notify waiting threads (those trying to remove) that an item is available
        }
    }

    /**
     * Removes and returns an item from the bag, blocking if the bag is empty until an item is available.
     * @return The integer removed from the bag.
     * @throws InterruptedException if interrupted while waiting.
     */
    public int remove() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait(); // Wait if the bag is empty
            }
            count--;
            int item = bagArray[count];
            // Since we decrement count first, the element is logically at index 'count' before return.
            // To maintain a simple structure without complex shifting, we just use the slot and rely on 'count'.
            // For this simulation, we decrement first, meaning the effective stored item is at index 'count' (which was the old count-1).
            // However, to correctly handle removal from a contiguous array managed by 'count', 
            // we should probably overwrite the slot that was conceptually last added, or handle array shifting.
            // Given the constraint complexity, we treat bagArray indices 0 to count-1 as occupied.
            // If we decrement count first, the element being removed was conceptually at index `count` (the old count).
            // Let's stick to the LIFO/FIFO behavior implied by stack/queue structure, but since it's a "bag," FIFO is simplest.
            // If we remove the item at index `count` (which was the last valid index), and then decrement count:
            int removedItem = bagArray[count]; // This relies on `count` being the *new* count after decrementing.
            bagArray[count] = 0; // Optional: Clear the slot if needed, but often skipped in simple queue implementations.
            count++; // Backtrack: The item to be removed was at index (new count). Let's rethink the removal mechanics for clarity.

            // --- Correcting Removal Logic ---
            // If count goes from N to N-1, the removed item was conceptually at index N-1 (if FIFO/LIFO) or we just mark it gone.
            // Since we incremented count to reflect the state *after* removal, let's fix the initial decrement:
            count--; // Now count is N-1. The item being removed was at index N-1 (the old count).
            int actualCount = count + 1; // Restore original count state for array access reference if needed, but simpler to just use the element.
            
            // Re-executing removal using the structure where `count` is the current number of elements (0 to CAPACITY).
            // If count was N > 0:
            int itemToRemove = bagArray[count]; // Access element at current 'count' index (0-based indexing for 1st item, N-1 for Nth item).
            // Wait, if count is the number of items (N), they occupy indices 0 to N-1.
            // So, if count = N, the item is at index N-1. We decrement first:
            // 1. Wait until count > 0.
            // 2. Decrement count (N -> N-1).
            // 3. Read item at index N-1.
            int actualItem = bagArray[count]; // This is the item at index 'count' *after* decrementing.
            // If we stick to this, the item removed is at index `count` (the new value of count).
            // We need to ensure the element we retrieved corresponds to the one intended for removal.

            /*
             * Simpler approach: If we are using `count` as the current size (1 to CAPACITY),
             * and indices 0 to count-1 are used:
             */
            
            // Original code flow reset for clarity in removal block:
            int item = bagArray[count - 1]; // Item to be removed is at the highest occupied index.
            bagArray[count - 1] = 0; // Optional cleanup
            count--; // Decrease count after successful removal.
            notifyAll(); // Notify waiting adders that space is available.
            return item;

        } catch (InterruptedException e) {
            // Re-throw if interrupted during wait/notify sequence, as required by the signature.
            Thread.currentThread().interrupt(); // Restore interrupt status before throwing if needed, though the method signature requires handling it.
            throw e; 
        }
    }


    /**
     * Corrected structure incorporating the fixes above to make the pairing Add/Remove consistent.
     */

    // Re-implementing the class entirely with correct block structure for submission readiness.
}


@ConcurrentBag.ThreadSafe
class ConcurrentBag {
    @interface ThreadSafe {}

    private static final int CAPACITY = 10;
    private int[] bagArray;
    private int count;

    public ConcurrentBag() {
        bagArray = new int[CAPACITY];
        count = 0; // count represents the current number of items (0 to CAPACITY)
    }

    /**
     * Adds an item to the bag, blocking if the bag is full until space becomes available.
     * @param item The integer to add.
     */
    public void add(int item) throws InterruptedException {
        synchronized (this) {
            // Wait while the bag is full
            while (count == CAPACITY) {
                wait(); 
            }

            // Add item: place at the current 'count' index (0-based)
            bagArray[count] = item;
            count++; // Increment count after successful placement

            // Notify potential waiters (those trying to remove)
            notifyAll(); 
        }
    }

    /**
     * Removes and returns an item from the bag, blocking if the bag is empty until an item is available.
     * @return The integer removed from the bag.
     * @throws InterruptedException if interrupted while waiting.
     */
    public int remove() throws InterruptedException {
        synchronized (this) {
            // Wait while the bag is empty
            while (count == 0) {
                wait(); // Block if nothing to remove
            }

            // Remove item: Item occupying the highest index (count - 1) based on current count.
            count--; // Decrement count first to correctly index the item being removed (0-based)
            int item = bagArray[count]; // Item was at index 'count' after decrementing.
            
            // Note: bagArray[count] technically now holds the removed item, matching our 0-based indexing model.

            // Notify potential waiters (those trying to add) that space is available
            notifyAll(); 
            return item;
        }
    }
}
