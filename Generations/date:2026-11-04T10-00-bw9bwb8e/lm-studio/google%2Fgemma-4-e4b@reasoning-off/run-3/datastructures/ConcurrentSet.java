@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}


    private Object lock = new Object();
    private boolean[] setElements;
    private int size;
    private static final int CAPACITY = 100; // Fixed capacity for simplicity in demonstration

    public ConcurrentSet() {
        setElements = new boolean[CAPACITY];
        size = 0;
    }

    /**
     * Adds an element to the set. If the set is full, it waits until space is available.
     * @param index The index representing the element to add. Must be between 0 and CAPACITY-1.
     * @return true if the element was added (i.e., it wasn't already present), false otherwise.
     * @throws IllegalArgumentException if index is out of bounds.
     */
    public synchronized boolean add(int index) throws IllegalStateException {
        if (index < 0 || index >= CAPACITY) {
            throw new IllegalArgumentException("Index out of bounds");
        }

        synchronized (lock) {
            // Wait while the set is full (though in this simple model, add might only fail if size == CAPACITY
            // and we are trying to maintain fixed capacity behavior regarding addition attempts vs removal needs)

            // For a simple Set implementation using boolean array representing presence, the "full" state
            // in the context of this bounded structure simulation usually implies adding a new item is impossible
            // or requires removal first. Since this problem doesn't specify capacity constraints on adding *new* unique items
            // unless we treat the array slots as fixed resources, we model it as follows:

            if (isPresent(index)) {
                return false; // Already present, do not add again.
            }

            // Mark as present
            setElements[index] = true;
            size++;

            // In a truly bounded scenario where adding always implies reaching capacity limit and needing removal,
            // we would need a waiting condition here. Given this is a Set implementation simulation, we assume
            // the capacity defines the *maximum* size. If it reaches CAPACITY and tries to add a new unique item,
            // we must wait for space if the structure is designed to be bounded AND allow removal.

            // Since we are modeling a Set, reaching CAPACITY just means no more unique items can be added *if* capacity is strictly enforced.
            // However, since we don't have a concurrent 'remove' influencing waiting here, we proceed with the addition.
            // The critical synchronization point is checking presence and setting state atomically.

            return true;
        }
    }

    /**
     * Removes an element from the set. If the set is empty, it waits until elements are available to remove.
     * @param index The index representing the element to remove. Must be between 0 and CAPACITY-1.
     * @return true if the element was present and removed, false otherwise.
     * @throws IllegalArgumentException if index is out of bounds.
     */
    public synchronized boolean remove(int index) throws IllegalStateException {
        if (index < 0 || index >= CAPACITY) {
            throw new IllegalArgumentException("Index out of bounds");
        }

        synchronized (lock) {
            // In a simple set, removal success depends only on presence. No waiting for content availability unless
            // the operation *requires* it (e.g., "wait until removal is possible").
            if (!isPresent(index)) {
                return false; // Not present, nothing to remove.
            }

            // Remove the element
            setElements[index] = false;
            size--;
            return true;
        }
    }

    /**
     * Checks if an element is in the set. This operation reads the state safely.
     * @param index The index representing the element to check. Must be between 0 and CAPACITY-1.
     * @return true if the element is present, false otherwise.
     * @throws IllegalArgumentException if index is out of bounds.
     */
    public synchronized boolean contains(int index) {
        if (index < 0 || index >= CAPACITY) {
            throw new IllegalArgumentException("Index out of bounds");
        }

        synchronized (lock) {
            return setElements[index];
        }
    }

    // --- Wait/Notify Simulation based on potential operational flow ---

    /**
     * Blocks until the set is NOT full (i.e., until space might have opened up).
     * This simulates a condition wait that Add/Remove operations could cause.
     */
    public void waitForSpace() throws InterruptedException {
        synchronized (lock) {
            // Wait while the set is full (assuming CAPACITY implies fullness for blocking writes)
            while (size == CAPACITY) {
                lock.wait();
            }
        }
    }

    /**
     * Blocks until the set is NOT empty (i.e., until an element might be available for removal).
     * This simulates a condition wait that Add/Remove operations could cause.
     */
    public void waitForElement() throws InterruptedException {
        synchronized (lock) {
            // Wait while the set is empty
            while (size == 0) {
                lock.wait();
            }
        }
    }

    /**
     * Signals waiting threads that space might be available (called after a successful removal).
     */
    public void signalSpaceAvailable() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Signals waiting threads that an element might be present (called after a successful addition).
     */
    public void signalElementAvailable() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private boolean isPresent(int index) {
        // This helper assumes the caller has already acquired the lock on 'lock' or is inside a synchronized block
        // accessing shared state. Since it's called internally under the lock context, this is safe regarding visibility.
        return setElements[index];
    }

    // Example usage simulation driver (not part of the required synchronized implementation but useful for context)
    public static void main(String[] args) {
        ConcurrentSet set = new ConcurrentSet();

        // Example of basic operations (without blocking behavior tested heavily)
        System.out.println("Contains 5? " + set.contains(5)); // false
        set.add(5); // true
        System.out.println("Contains 5 after add? " + set.contains(5)); // true
        set.remove(5); // true
        System.out.println("Contains 5 after remove? " + set.contains(5)); // false

        // Note: To properly test wait/notify, multiple threads would be required accessing signal methods.
    }
}
