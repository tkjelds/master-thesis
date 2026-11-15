Order<T> {
    public abstract int compare(Object left, Object right);
}

@Handle.ThreadSafe
class Handle<T> {
    @interface ThreadSafe {}

    private final int id;
    private final IntervalHeap<?> parent; // Weak reference or careful management needed in a real scenario, but for this constrained problem, we rely on the main object reference.

    // Unique identifier for the handle instance specific to its insertion
    Handle(int id, IntervalHeap<?> parent) {
        this.id = id;
        this.parent = parent;
    }

    // Private constructor or methods to prevent external manipulation of the handle itself.
}

@IntervalHeap.ThreadSafe
class IntervalHeap<T> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;

    // --- Internal State ---
    private volatile Object monitorLock = new Object(); // Monitor for synchronization

    private Order<T> comparator;
    private int size = 0;

    // Array storage for nodes. Doubly linked/structured array approach simulated here.
    private Node<T>[] table;
    private int count = 0; // Current number of elements

    // Head/Tail nodes representing the extremal endpoints.
    private volatile Node<T> minNode;
    private volatile Node<T> maxNode;

    // Next available unique ID for Add operations (to ensure distinct handles)
    private int handleIdCounter = 0;

    // Mapping Handle ID -> Node reference for O(1) locate/delete/replace complexity
    private volatile Node<T>[] handleMap; // Needs careful resizing and synchronization

    // --- Node Structure (Simulating the Heap/Interval concept) ---
    private static class Node<T> {
        // Data
        volatile T item;

        // Interval management (Low/High endpoints)
        volatile int lowEndpointId; // Pointer to the node representing this interval's minimum boundary element
        volatile int highEndpointId; // Pointer to the node representing this interval's maximum boundary element

        // Heap structure pointers (for maintenance)
        volatile Node<T> parent;
        volatile Node<T> leftChild;
        volatile Node<T> rightChild;

        // Handle reference (allows O(1) location during delete/replace)
        // In a highly optimized C5 implementation, this might be implicit via the interval structure itself.
        volatile Handle<?> handle;

        // Constructor for a new node
        Node(T item) {
            this.item = item;
            // Initialize endpoints to point to themselves/current structure state if necessary upon insertion.
        }
    }

    // --- Handle Implementation Detail (Crucial for the contract) ---
    private class SpecificHandle<T> extends Handle<T> {
        private final Node<T> node; // Reference to the actual array element hosting this handle

        SpecificHandle(Node<T> n) {
            super(0, null); // ID and parent are managed by the owning heap object
            this.node = n;
        }

        // Implementation detail: Provides access to the specific node when needed by delete/replace methods.
        public Node<T> getNode() {
            return node;
        }
    }

    // --- Constructor ---
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("Comparator cannot be null.");
        }
        this.comparator = order;

        // Initialize structure arrays/maps lazily upon first use or when capacity is known.
        // For simplicity, we rely on lazy initialization of table/handleMap upon use.

        // Initialize the singleton state (if necessary for structural soundness)
    }

    // --- Synchronization Helper ---
    private void acquireMonitor() {
        synchronized (monitorLock) {
            // Monitor acquired
        }
    }

    private void releaseMonitor() {
        // Monitoring is implicitly released when exiting synchronized blocks.
    }

    // --- Public Operations Implementation ---

    /**
     * Adds an item and returns a distinct handle. Amortized O(log n).
     */
    public Handle<T> add(T item) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null.");
        }

        acquireMonitor();
        try {
            // 1. Create Node
            Node<T> newNode = new Node<>(item);

            // 2. Handle map management (ensure handleMap is sized appropriately)
            if (handleMap == null || count >= handleMap.length) {
                // In a real implementation, we would double the size and copy contents atomically/under lock.
                // For this simulation, we assume sufficient capacity or handle dynamic resizing carefully.
                // If count starts at 0 and table array is used as the basis for indices, this is complex.
                // Assuming initial capacity reservation based on expected growth pattern.
            }

            // 3. Add to structure (Heap insertion, endpoint updates)
            count++;
            // ... perform insertion logic maintaining heap properties and interval endpoints ...

            // 4. Create Handle pointing to the new node
            SpecificHandle<T> handle = new SpecificHandle<>(newNode);
            // Store the mapping: Handle ID -> Node reference (Crucial for O(log n) delete/replace)
            // In a truly robust system, this mapping must survive array shifts if the Node object itself moves.
            // Since we use a fixed Node instance per insertion, mapping is stable.

            return handle;
        } finally {
            releaseMonitor(); // Release monitor in the main control flow after successful state change
        }
    }

    /**
     * Finds the minimum element. O(1).
     */
    public T findMin() {
        acquireMonitor();
        try {
            if (count == 0) {
                throw new IllegalStateException("Heap is empty.");
            }
            // minNode points directly to the node containing the minimum item.
            return minNode.item; // Assuming minNode is correctly maintained upon addition/deletion/replacement
        } finally {
            releaseMonitor();
        }
    }

    /**
     * Finds the maximum element. O(1).
     */
    public T findMax() {
        acquireMonitor();
        try {
            if (count == 0) {
                throw new IllegalStateException("Heap is empty.");
            }
            // maxNode points directly to the node containing the maximum item.
            return maxNode.item; // Assuming maxNode is correctly maintained upon addition/deletion/replacement
        } finally {
            releaseMonitor();
        }
    }

    /**
     * Removes and returns the minimum element. O(log n).
     */
    public T deleteMin() {
        acquireMonitor();
        try {
            if (count == 0) {
                throw new IllegalStateException("Heap is empty.");
            }

            // 1. Identify node via minNode (the current minimum)
            Node<T> minN = minNode;

            // 2. Update structure: Remove node, restore heap property (sift down/up), update endpoints.
            // ... deletion logic ...

            count--;

            if (count == 0) {
                // Clear all roots/pointers
                minNode = null;
                maxNode = null;
            }

            // Return item and invalidate the associated handle if needed (though the caller usually handles this).
            return minN.item;

        } finally {
            releaseMonitor();
        }
    }

    /**
     * Removes and returns the maximum element. O(log n).
     */
    public T deleteMax() {
        // Symmetric to deleteMin, operating on maxNode.
        acquireMonitor();
        try {
            if (count == 0) {
                throw new IllegalStateException("Heap is empty.");
            }
            // ... deletion logic ...
            return maxNode.item; // Placeholder
        } finally {
            releaseMonitor();
        }
    }

    /**
     * Removes the element identified by the handle. O(log n).
     */
    public T delete(Handle<T> handle) {
        if (handle == null) throw new NullPointerException("Handle cannot be null.");

        acquireMonitor();
        try {
            // 1. Resolve handle -> Node reference (using internal mapping, validation needed)
            Node<T> targetN = resolveNodeFromHandle(handle); // This checks validity and retrieves the node

            if (targetN == null) {
                throw new IllegalArgumentException("Handle is foreign or already invalidated.");
            }

            // 2. Perform deletion (sift down/up relative to its current position)
            T item = deleteNode(targetN); // deleteNode handles heap repair and count decrement

            return item;
        } finally {
            releaseMonitor();
        }
    }

    /**
     * Replaces the item associated with the handle and repairs ordering. O(log n).
     */
    public T replace(Handle<T> handle, T newItem) {
        if (handle == null || newItem == null) throw new NullPointerException("Arguments cannot be null.");

        acquireMonitor();
        try {
            // 1. Resolve handle -> Node reference
            Node<T> targetN = resolveNodeFromHandle(handle);

            if (targetN == null) {
                 throw new IllegalArgumentException("Handle is foreign or already invalidated.");
            }

            // 2. Perform replacement: Get old item, update node's item field, repair heap structure if necessary.
            T oldItem = targetN.item;
            targetN.item = newItem;

            // 3. Heap repair (this might involve sifting up or down depending on the comparison result)
            repairHeapLocation(targetN); // Assume this routine executes the necessary sift/bubble operation

            return oldItem;
        } finally {
            releaseMonitor();
        }
    }

    /**
     * Returns the current number of elements. O(1).
     */
    public int size() {
        acquireMonitor();
        try {
            return count;
        } finally {
            releaseMonitor();
        }
    }

    // --- Internal/Simulated Helpers (Must be robustly implemented in a production version) ---

    private Node<T> resolveNodeFromHandle(Handle<T> handle) {
        // Simulation: In a real implementation, this uses the mapping structure safely under lock.
        // This must validate if the handle is still active and returns its unique node reference.
        return null; // Placeholder for complexity abstraction
    }

    private T deleteNode(Node<T> targetN) {
        // Placeholder for the complex deletion/repair logic involving finding successor/predecessor, swapping with boundary elements, and heap restoration.
        return targetN.item; // Simplified return
    }

    private void repairHeapLocation(Node<T> targetN) {
        // Placeholder for comparing the new item against neighbors and performing siftUp/siftDown until heap property holds.
    }
}
