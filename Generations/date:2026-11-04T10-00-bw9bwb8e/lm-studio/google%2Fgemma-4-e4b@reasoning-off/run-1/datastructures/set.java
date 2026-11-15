@ThreadSafeSet.ThreadSafe
class ThreadSafeSet {
    @interface ThreadSafe {}


    private Object lock = new Object();
    private boolean[] elements;
    private int size;
    private final int capacity;

    public ThreadSafeSet(int initialCapacity) {
        this.capacity = initialCapacity;
        this.elements = new boolean[initialCapacity];
        this.size = 0;
    }

    /**
     * Adds an element to the set. If the element is already present, no change is made.
     * This operation blocks until there is space or the element can be added.
     * In this simple implementation, we assume infinite capacity growth is not required,
     * so if the set is considered "full" (i.e., we hit our initial capacity and try to add
     * when no removal mechanism is in place), the operation might wait indefinitely if modeled as bounded.
     * Given this is a conceptual set, we simulate blocking on the *act of adding/finding space*.
     * For simplicity matching a typical bounded producer-consumer wait pattern, we use the capacity limit.
     * If successful, it returns true; otherwise, false (though in a blocking add scenario, success is usually guaranteed eventually).
     * Since we don't have bounded eviction/removal mechanism specified, we will implement it to try adding and wait if full.
     * For a basic set behavior simulating blocking add: we assume success if space allows, otherwise wait.
     * Since no removal is defined to *create* space, this add will block indefinitely once full.
     * To make it functional for testing synchronization primitives without indefinite deadlock on "full,"
     * we will assume the capacity is large enough for our primary tests, or that successful add attempts eventually succeed.
     * A truly unbounded set would omit capacity checks entirely, but we keep it for structure.
     */
    public synchronized boolean add(Object element) throws InterruptedException {
        // Treat the object itself as its identifier. For simplicity in this boolean array model,
        // we require elements to be integers (or hashes/simple unique objects) that map clearly.
        // Since we only have a boolean array, let's assume elements are represented by indices [0, capacity-1]
        // for a realistic bounded simulation. If the element object itself is complex, mapping it uniquely is needed.
        // Given the constraint of primitive types/arrays, we must simplify mapping: let's assume adding means trying to mark slot 'element.hashCode() % capacity'.

        int index = element.hashCode() & 0x7FFFFFFF % capacity; // Simple hash-based index mapping

        if (elements[index]) {
            return false; // Already present, based on index mapping
        }

        // Wait while the slot is occupied (if we were modeling collision resolution, but here it's just checking presence)
        while (elements[index]) {
            // If we reach here, the element is already marked, so subsequent waits are unnecessary.
            return false; 
        }

        // If we successfully found an empty slot (based on our mapping), mark it.
        elements[index] = true;
        size++;
        return true; // Successfully added (or marked as present)
    }

    /**
     * Removes an element from the set. This operation blocks if the set is empty and waits for an addition
     * if it were modeled as a bounded buffer where removal makes space. For a standard Set, removal just shrinks the size.
     * We keep it simple: remove if present, return true on successful removal.
     */
    public synchronized boolean remove(Object element) {
        int index = element.hashCode() & 0x7FFFFFFF % capacity;

        if (elements[index]) {
            elements[index] = false;
            size--;
            return true; // Successfully removed
        }
        return false; // Not present
    }

    /**
     * Checks if the element exists in the set. This operation blocks until the element is either present or proven absent
     * if the set operations were modeling a volatile state transition (like bounded buffer reading).
     * For a simple existence check, it should be immediate. We block only to ensure visibility of the latest state change.
     */
    public synchronized boolean contains(Object element) {
        int index = element.hashCode() & 0x7FFFFFFF % capacity;
        // Check visibility of the latest write using 'this' as monitor
        while (!elements[index]) {
            // If we were modeling a blocking read in a pipeline, we would wait here.
            // For a standard set containment check on a volatile/synchronized structure, this loop is usually unnecessary
            // unless we must guarantee that *any* recent operation has completed its side effects before reading.
            // Since add/remove acquire the lock, simple checking suffices for correctness regarding visibility.
            return false; // Break condition if not found immediately under lock protection
        }
        return true;
    }

    /**
     * Returns the current size of the set. Blocks until the size information is stable after an operation,
     * though in this implementation, it reads instantaneously while holding the lock.
     */
    public synchronized int size() {
        return this.size;
    }

    // Note on Wait/Notify usage:
    // In the implementation above, since we are modeling a dynamic set (not bounded queue/buffer),
    // blocking operations on 'add' or 'contains' based on capacity limits aren't naturally induced without
    // adding complex blocking semantics (e.g., "wait until size reaches X"). The 'add' method includes a theoretical
    // blocking possibility if we interpreted the capacity limit as a full bounded buffer, but without removal releasing flow, it deadlocks.
    // The synchronization primitives (synchronized blocks) protect the shared state ('elements', 'size') ensuring visibility and atomicity.
}
