@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}


    private Object[] setArray;
    private int size;
    private int capacity;

    // Initial capacity chosen to be a power of 2 for simple modulo hashing, though
    // we won't use array indices directly as buckets in a true hash map for simplicity
    // and focusing on the core synchronization pattern required.
    private static final int INITIAL_CAPACITY = 16;

    /**
     * Constructs a new ConcurrentSet with an initial capacity.
     */
    public ConcurrentSet() {
        this(INITIAL_CAPACITY);
    }

    /**
     * Constructs a new ConcurrentSet with the specified initial capacity.
     */
    @SuppressWarnings("unchecked")
    public ConcurrentSet(int initialCapacity) {
        this.capacity = initialCapacity;
        // Using Object[] allows us to store different types, matching Set behavior.
        this.setArray = new Object[capacity];
        this.size = 0;
    }

    /**
     * Adds an element to the set. If the element is already present, the set remains unchanged.
     * This operation blocks until the insertion succeeds or the element is already present,
     * and guarantees atomicity regarding size updates.
     *
     * Note: In a true dynamic set, resizing would also be involved here. For this simplified
     * bounded array implementation focusing on synchronization primitives, we assume the initial capacity
     * is sufficient or that resizing logic (if needed) would also use wait/notifyAll correctly.
     * Since we are modeling a simple Set synchronization wrapper, we simulate successful addition up to capacity.
     * If the set were truly bounded and reached capacity, a specific policy (like blocking insertion)
     * would need to be defined. Here, we simulate successful addition up to the current capacity limit
     * enforced by the array structure.
     * @param element The element to add.
     */
    public synchronized void add(Object element) throws InterruptedException {
        // 1. Check existence first (optimistic check)
        if (contains(element)) {
            return; // Already present, do nothing.
        }

        // 2. If the set is full (based on array capacity), we must block until space becomes available
        // In this simple bounded model, if size == capacity, we would wait for a remove operation.
        // Since this is an Add operation on a theoretically unbounded set simulated by a growing structure,
        // or if we assume it's bounded and full, we need a mechanism. For simplicity matching the prompt constraints:
        // We assume capacity allows for growth or that insertion is guaranteed if not full.

        if (size >= capacity) {
            // In a real scenario, resizing would happen here. If we don't resize, and it's full,
            // a wait/notifyAll cycle would be necessary if insertion itself blocks indefinitely.
            // Since we assume the initial structure is large enough for typical use, we proceed if not full.
            // If it MUST block until space opens up (and capacity is fixed), we'd wait here.
            // Given the ambiguity, we proceed with insertion if not full, otherwise, we wait/throw depending on policy.
            // Policy adopted: If array is full and no resizing happens, subsequent adds will fail unless removal occurs.
            // To allow successful insertion until capacity is hit:
            while (size >= capacity) {
                wait(); // Wait if conceptually full and we must wait for removal.
            }
        }

        // 3. Insertion logic (only runs if space is conceptually available)
        if (size < capacity) {
            // Find a spot for insertion (simplistic linear search/placement)
            int index = -1;
            for (int i = 0; i < capacity; i++) {
                if (setArray[i] == null) {
                    index = i;
                    break;
                }
            }

            if (index != -1) {
                setArray[index] = element;
                size++;
                // Notify waiting threads (e.g., those blocked trying to add or remove)
                notifyAll();
            } else {
                 // This case implies size == capacity but we somehow failed to find a null spot, 
                 // which shouldn't happen if size tracking is correct and capacity > 0.
            }
        }
    }

    /**
     * Contains the element in the set. This operation observes the current state atomically.
     * @param element The element to search for.
     * @return true if the element is present, false otherwise.
     */
    public boolean contains(Object element) {
        // Read operations must also be protected to ensure visibility of the most recent writes (e.g., during add).
        // Synchronizing on 'this' ensures we read the size and array contents atomically relative to writers.
        synchronized (this) {
            for (int i = 0; i < capacity; i++) {
                if (setArray[i] != null && setArray[i].equals(element)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes the specified element from this set if it is present.
     * This operation blocks until the removal is successful or the element was never present.
     * If we model a strict "remove only if present" policy, blocking might imply waiting for
     * internal invariants to align, but generally removal should succeed if present.
     * We implement it to be non-blocking regarding existence check, but block on structural change.
     * @param element The element to remove.
     * @return true if the set contained the specified element, false otherwise.
     */
    public synchronized boolean remove(Object element) throws InterruptedException {
        int initialSize = size;

        // Search and remove attempt must be atomic relative to adds/removes.
        for (int i = 0; i < capacity; i++) {
            if (setArray[i] != null && setArray[i].equals(element)) {
                setArray[i] = null; // Remove reference
                size--;
                // Notify waiters that the set structure has changed (e.g., space available for adds)
                notifyAll();
                return true;
            }
        }

        // If we reached here, the element was not found. Return false.
        return false;
    }

    /**
     * Returns the current number of elements in the set. This operation observes the size atomically.
     * @return The current number of elements.
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }

    // --- Example Main Method for self-testing context (not part of the production requirement but useful) ---
    /*
    public static void main(String[] args) throws InterruptedException {
        ConcurrentSet set = new ConcurrentSet(5);

        Runnable adder = () -> {
            try {
                System.out.println(Thread.currentThread().getName() + ": Trying to add A");
                set.add("A");
                System.out.println(Thread.currentThread().getName() + ": Added A. Current size: " + set.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable remover = () -> {
            try {
                System.out.println(Thread.currentThread().getName() + ": Trying to remove A");
                if (set.remove("A")) {
                    System.out.println(Thread.currentThread().getName() + ": Successfully removed A. Current size: " + set.size());
                } else {
                    System.out.println(Thread.currentThread().getName() + ": A not found.");
                }
            } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
            }
        };

        Thread t1 = new Thread(adder, "Adder-1");
        Thread t2 = new Thread(remover, "Remover-1");

        t1.start();
        Thread.sleep(50); // Give Adder a chance to start/add
        t2.start();

        t1.join();
        t2.join();
    }
    */
}
